package sh.hutch.postmaster.services

import com.icegreen.greenmail.util.GreenMail
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.mail.Flags
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sh.hutch.postmaster.testsupport.GreenMailTestResource
import sh.hutch.postmaster.testsupport.InjectGreenMail
import sh.hutch.postmaster.testsupport.IntegrationProfile
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.Attachment
import sh.hutch.postmaster.testsupport.TestMessages.message
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reading and changing mail over IMAP and forwarding over SMTP (REQ
 * Fetching emails, move, delete, mark_read, mark_unread, forward_email;
 * CLAR Q6, Q7, Q14, D7, D16), against GreenMail standing in for Dovecot.
 */
@QuarkusTest
@TestProfile(IntegrationProfile::class)
@WithTestResource(GreenMailTestResource::class)
class MailboxClientTest {
  @field:InjectGreenMail
  lateinit var greenMail: GreenMail

  @Inject
  lateinit var mailbox: MailboxClient

  @Inject
  lateinit var settings: SettingsService

  @TempDir
  lateinit var dir: Path

  @BeforeEach
  fun pointAtGreenMail() {
    greenMail.purgeEmailFromAllMailboxes()
    settings.update("imap.host", "127.0.0.1")
    settings.update("imap.port", greenMail.imap.port.toString())
    settings.update("imap.security", "none")
    settings.update("imap.username", GreenMailTestResource.USER)
    settings.update("imap.password", GreenMailTestResource.PASSWORD)
    settings.update("smtp.host", "127.0.0.1")
    settings.update("smtp.port", greenMail.smtp.port.toString())
    settings.update("smtp.security", "none")
    settings.update("smtp.from_address", "postmaster@fulcrum.test")
    TestMessages.createFolders(greenMail, "Trash", "Folders/Bills")
  }

  private fun inbox(vararg messages: MimeMessage) = TestMessages.append(greenMail, "INBOX", *messages)

  private fun uidOf(subject: String): Long = mailbox.listInbox(null).single { it.subject == subject }.uid

  @Test
  fun `the inbox is listed newest first with Message-ID, UID and UIDVALIDITY`() {
    // REQ Fetching emails: "from most recently received to least"; CLAR Q14, D7
    inbox(
        message("<old@mail.example>", "Old", sent = Instant.parse("2026-10-18T09:00:00Z")),
        message("<new@mail.example>", "New", sent = Instant.parse("2026-10-20T09:00:00Z")),
        message("<middle@mail.example>", "Middle", sent = Instant.parse("2026-10-19T09:00:00Z"))
    )

    val listed = mailbox.listInbox(null)

    assertEquals(listOf("New", "Middle", "Old"), listed.map { it.subject })
    assertEquals(listOf("new@mail.example", "middle@mail.example", "old@mail.example"), listed.map { it.messageId })
    assertEquals(Instant.parse("2026-10-20T09:00:00Z"), listed[0].receivedAt)
    assertTrue(listed.all { it.uid > 0 && it.uidValidity > 0 })
    assertEquals(3, listed.map { it.uid }.toSet().size)
    assertEquals("sender@example.test", listed[0].sender)
  }

  @Test
  fun `only mail received since the lookback is listed`() {
    // CLAR Q14
    inbox(
        message("<a@mail.example>", "Before", sent = Instant.parse("2026-10-01T00:00:00Z")),
        message("<b@mail.example>", "After", sent = Instant.parse("2026-10-21T00:00:00Z"))
    )

    assertEquals(listOf("After"), mailbox.listInbox(Instant.parse("2026-10-15T00:00:00Z")).map { it.subject })
  }

  @Test
  fun `a message without a Message-ID gets a generated id`() {
    inbox(message(null, "No id"))

    val listed = mailbox.listInbox(null).single()
    assertTrue(listed.messageId.startsWith(MessageIds.GENERATED_PREFIX), listed.messageId)
    assertEquals(listed.messageId, mailbox.listInbox(null).single().messageId, "the same id each time")
  }

  @Test
  fun `reading mail never marks it read`() {
    // CLAR D16
    inbox(message("<peek@mail.example>", "Peek", html = "<p>Hi</p>"))

    val parsed = mailbox.fetch("INBOX", uidOf("Peek"))

    assertFalse(parsed.isRead)
    val flags = TestMessages.folderContents(greenMail, "INBOX").single().second
    assertFalse(flags.contains(Flags.Flag.SEEN), "still unread on the server")
  }

  @Test
  fun `a plain text message is parsed`() {
    inbox(message("<plain@mail.example>", "Plain", from = "Brunswick South PS <office@brunswicksouth.example>", text = "Hello there"))

    val parsed = mailbox.fetch("INBOX", uidOf("Plain"))

    assertEquals("Hello there", parsed.text?.trim())
    assertNull(parsed.html)
    assertEquals("office@brunswicksouth.example", parsed.sender)
    assertEquals("Brunswick South PS", parsed.senderName)
    assertEquals(listOf(GreenMailTestResource.ADDRESS), parsed.recipients)
    assertEquals("plain@mail.example", parsed.messageId)
    assertEquals("INBOX", parsed.folder)
  }

  @Test
  fun `an HTML-only message gets its text from the HTML`() {
    inbox(message("<html@mail.example>", "Html", text = null, html = "<html><body><h1>Title</h1><p>Body text</p></body></html>"))

    val parsed = mailbox.fetch("INBOX", uidOf("Html"))

    assertTrue(parsed.html!!.contains("<h1>Title</h1>"))
    assertTrue(parsed.text!!.contains("Title") && parsed.text!!.contains("Body text"), parsed.text)
    assertFalse(parsed.text!!.contains("<"), parsed.text)
  }

  @Test
  fun `a multipart alternative message has both text and HTML`() {
    inbox(message("<alt@mail.example>", "Alternative", text = "Text version", html = "<p>HTML version</p>"))

    val parsed = mailbox.fetch("INBOX", uidOf("Alternative"))

    assertEquals("Text version", parsed.text?.trim())
    assertTrue(parsed.html!!.contains("HTML version"))
    assertTrue(parsed.attachments.isEmpty())
  }

  @Test
  fun `attachments are listed and saved byte for byte, with non-ASCII names intact`() {
    // REQ download_attachments; Fetching emails (count of attachments)
    val csv = "a,b\n1,2\n".toByteArray()
    inbox(
        message(
            "<bill@mail.example>", "Grüße zur Rechnung ✓",
            text = "See attached",
            attachments = listOf(
                Attachment("Rechnung März.pdf", "application/pdf", TestMessages.PDF),
                Attachment("data.csv", "text/csv", csv)
            )
        )
    )
    val uid = uidOf("Grüße zur Rechnung ✓")

    val parsed = mailbox.fetch("INBOX", uid)

    assertEquals("Grüße zur Rechnung ✓", parsed.subject)
    assertEquals("See attached", parsed.text?.trim())
    assertEquals(listOf("Rechnung März.pdf", "data.csv"), parsed.attachments.map { it.filename })
    assertTrue(parsed.attachments[0].contentType.startsWith("application/pdf"))
    val target = dir.resolve("nested/dir/bill.pdf")
    val size = mailbox.saveAttachment("INBOX", uid, parsed.attachments[0].partPath, target)
    assertArrayEquals(TestMessages.PDF, Files.readAllBytes(target))
    assertEquals(TestMessages.PDF.size.toLong(), size)
    mailbox.saveAttachment("INBOX", uid, parsed.attachments[1].partPath, dir.resolve("data.csv"))
    assertArrayEquals(csv, Files.readAllBytes(dir.resolve("data.csv")))
  }

  @Test
  fun `a missing message is an error`() {
    assertThrows<MailboxException> { mailbox.fetch("INBOX", 999_999) }
  }

  @Test
  fun `mail is moved to another folder`() {
    // REQ move
    inbox(message("<move@mail.example>", "Move me"))

    mailbox.move("INBOX", uidOf("Move me"), "Folders/Bills")

    assertEquals(listOf("Move me"), TestMessages.folderContents(greenMail, "Folders/Bills").map { it.first })
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").none { it.first == "Move me" })
  }

  @Test
  fun `moving to a folder that doesn't exist is an error`() {
    inbox(message("<nowhere@mail.example>", "Nowhere"))

    assertThrows<MailboxException> { mailbox.move("INBOX", uidOf("Nowhere"), "Folders/Missing") }
    assertEquals(1, TestMessages.folderContents(greenMail, "INBOX").size, "the message stays put")
  }

  @Test
  fun `deleting moves mail to the trash folder`() {
    // REQ delete: "move it to Trash"; CLAR Q6 (the trash folder is a setting)
    inbox(message("<delete@mail.example>", "Delete me"))

    mailbox.delete("INBOX", uidOf("Delete me"))

    assertEquals(listOf("Delete me"), TestMessages.folderContents(greenMail, "Trash").map { it.first })
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").isEmpty())
  }

  @Test
  fun `the trash folder comes from the settings`() {
    TestMessages.createFolders(greenMail, "Deleted Items")
    settings.update("imap.trash_folder", "Deleted Items")
    try {
      inbox(message("<bin@mail.example>", "Bin me"))

      mailbox.delete("INBOX", uidOf("Bin me"))

      assertEquals(listOf("Bin me"), TestMessages.folderContents(greenMail, "Deleted Items").map { it.first })
    } finally {
      settings.update("imap.trash_folder", "Trash")
    }
  }

  @Test
  fun `mail can be marked read and unread`() {
    // REQ mark_read, mark_unread; Viewing an individual email 9
    inbox(message("<flags@mail.example>", "Flags"))
    val uid = uidOf("Flags")

    mailbox.setRead("INBOX", uid, true)
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))
    assertTrue(mailbox.fetch("INBOX", uid).isRead)

    mailbox.setRead("INBOX", uid, false)
    assertFalse(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))
  }

  @Test
  fun `folders are listed by full name`() {
    // REQ Viewing an individual email 8 (move to a different IMAP folder)
    val folders = mailbox.listFolders()

    assertTrue(folders.containsAll(listOf("INBOX", "Trash", "Folders/Bills")), folders.toString())
    assertEquals(folders.sorted(), folders)
  }

  @Test
  fun `a forward is an inline forward with the quoted body and the attachments, sent by SMTP`() {
    // REQ forward_email; CLAR Q7 (inline forward through the SMTP settings)
    inbox(
        message(
            "<fwd@mail.example>", "Payslip for October",
            from = "Payroll <payroll@employer.example>",
            text = "Your payslip is attached.",
            html = "<p>Your <b>payslip</b> is attached.</p>",
            attachments = listOf(Attachment("payslip.pdf", "application/pdf", TestMessages.PDF))
        )
    )

    mailbox.forward("INBOX", uidOf("Payslip for October"), "accountant@example.test")

    assertTrue(greenMail.waitForIncomingEmail(5000, 1))
    val sent = greenMail.receivedMessages.single { it.allRecipients.any { r -> r.toString() == "accountant@example.test" } }
    assertEquals("Fwd: Payslip for October", sent.subject)
    assertEquals("postmaster@fulcrum.test", sent.from.single().toString())
    val parts = flatten(sent)
    val text = parts.first { it.isMimeType("text/plain") && it.fileName == null }.content as String
    assertTrue(text.contains("---------- Forwarded message ---------"), text)
    assertTrue(text.contains("From: Payroll <payroll@employer.example>"), text)
    assertTrue(text.contains("Subject: Payslip for October"), text)
    assertTrue(text.contains("Your payslip is attached."), text)
    val html = parts.first { it.isMimeType("text/html") }.content as String
    assertTrue(html.contains("<b>payslip</b>"), html)
    val attachment = parts.single { it.fileName != null }
    assertEquals("payslip.pdf", MimeUtility.decodeText(attachment.fileName))
    assertArrayEquals(TestMessages.PDF, attachment.inputStream.readAllBytes())
  }

  @Test
  fun `forwarding to an invalid address is an error`() {
    inbox(message("<bad@mail.example>", "Bad forward"))

    assertThrows<MailboxException> { mailbox.forward("INBOX", uidOf("Bad forward"), "not an address") }
  }

  @Test
  fun `an unreachable server is reported as a mailbox error`() {
    settings.update("imap.port", "1")

    val error = assertThrows<MailboxException> { mailbox.listInbox(null) }
    assertTrue(error.message!!.isNotBlank())
  }

  private fun flatten(part: Part): List<Part> =
      if (part.isMimeType("multipart/*")) {
        val multipart = part.content as Multipart
        (0 until multipart.count).flatMap { flatten(multipart.getBodyPart(it)) }
      } else {
        listOf(part)
      }
}
