package sh.hutch.postmaster.testsupport

import com.icegreen.greenmail.util.GreenMail
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import java.time.Instant
import java.util.Date
import java.util.Properties

/** Builds MIME messages for the IMAP tests and delivers them to GreenMail */
object TestMessages {
  private val session: Session = Session.getInstance(Properties())

  /** A file attached to a message */
  class Attachment(val filename: String, val contentType: String, val bytes: ByteArray)

  /**
   * A message with a plain text part, an HTML part (both makes
   * multipart/alternative), and any attachments (multipart/mixed).
   */
  fun message(
      messageId: String?,
      subject: String,
      from: String = "Sender <sender@example.test>",
      to: String = GreenMailTestResource.ADDRESS,
      text: String? = "Plain text body",
      html: String? = null,
      attachments: List<Attachment> = emptyList(),
      sent: Instant = Instant.parse("2026-10-20T09:00:00Z")
  ): MimeMessage {
    val message = object : MimeMessage(session) {
      // Keep our Message-ID (or none) instead of one generated on save
      override fun updateMessageID() {
        if (messageId == null) removeHeader("Message-ID") else setHeader("Message-ID", messageId)
      }
    }
    message.setFrom(InternetAddress(from))
    message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
    message.setSubject(subject, "UTF-8")
    message.sentDate = Date.from(sent)

    val body: Any = when {
      text != null && html != null -> MimeMultipart("alternative").apply {
        addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
        addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
      }
      html != null -> html
      else -> text.orEmpty()
    }
    if (attachments.isEmpty()) {
      when (body) {
        is MimeMultipart -> message.setContent(body)
        else -> if (html != null && text == null) message.setContent(html, "text/html; charset=UTF-8") else message.setText(text.orEmpty(), "UTF-8")
      }
    } else {
      val mixed = MimeMultipart("mixed")
      mixed.addBodyPart(MimeBodyPart().apply {
        when (body) {
          is MimeMultipart -> setContent(body)
          else -> if (html != null && text == null) setContent(html, "text/html; charset=UTF-8") else setText(text.orEmpty(), "UTF-8")
        }
      })
      for (attachment in attachments) {
        mixed.addBodyPart(MimeBodyPart().apply {
          dataHandler = DataHandler(ByteArrayDataSource(attachment.bytes, attachment.contentType))
          fileName = MimeUtility.encodeText(attachment.filename, "UTF-8", null)
          disposition = MimeBodyPart.ATTACHMENT
          // Real attachments are binary and sent as base64; ASCII test bytes would
          // otherwise go as text, with their line endings changed
          setHeader("Content-Transfer-Encoding", "base64")
        })
      }
      message.setContent(mixed)
    }
    message.saveChanges()
    return message
  }

  /**
   * Appends [messages] to [folder] over IMAP, creating the folder if needed.
   * The IMAP received time (INTERNALDATE) of each is its sent date, so tests
   * control the order.
   */
  fun append(greenMail: GreenMail, folder: String, vararg messages: MimeMessage) {
    withStore(greenMail) { store ->
      val target = store.getFolder(folder)
      if (!target.exists()) {
        target.create(Folder.HOLDS_MESSAGES)
      }
      target.appendMessages(messages)
    }
  }

  /** Creates folders in the test mailbox */
  fun createFolders(greenMail: GreenMail, vararg names: String) {
    withStore(greenMail) { store ->
      for (name in names) {
        val folder = store.getFolder(name)
        if (!folder.exists()) {
          folder.create(Folder.HOLDS_MESSAGES)
        }
      }
    }
  }

  /** Runs [block] with an IMAP store logged in to the test mailbox */
  fun <T> withStore(greenMail: GreenMail, block: (Store) -> T): T {
    val properties = Properties()
    properties["mail.imap.peek"] = "true"
    val store = Session.getInstance(properties).getStore("imap")
    store.connect("127.0.0.1", greenMail.imap.port, GreenMailTestResource.USER, GreenMailTestResource.PASSWORD)
    return store.use(block)
  }

  /** The subjects and flags of the messages in [folder], read without changing them */
  fun folderContents(greenMail: GreenMail, folder: String): List<Pair<String?, Flags>> =
      withStore(greenMail) { store ->
        val target = store.getFolder(folder)
        if (!target.exists()) {
          return@withStore emptyList()
        }
        target.open(Folder.READ_ONLY)
        try {
          target.messages.map { it.subject to it.flags }
        } finally {
          target.close(false)
        }
      }

  /** A small but valid PDF */
  val PDF: ByteArray = """%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj
trailer<</Root 1 0 R>>
%%EOF
""".toByteArray()
}
