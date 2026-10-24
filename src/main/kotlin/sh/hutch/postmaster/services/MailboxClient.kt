package sh.hutch.postmaster.services

import jakarta.activation.DataHandler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.UIDFolder
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.util.ByteArrayDataSource
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import sh.hutch.postmaster.models.AttachmentPart
import sh.hutch.postmaster.models.MailboxMessage
import sh.hutch.postmaster.models.ParsedMessage
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Date
import java.util.Properties

/**
 * Reads and changes the mailbox in Dovecot over IMAP, and forwards email over SMTP, using the imap.*
 * and smtp.* settings. Each call opens and closes its own connection. Messages are read with
 * BODY.PEEK, so reading never marks them as read (CLARIFICATIONS.md D16).
 */
@ApplicationScoped
class MailboxClient(private val settings: SettingsService) {

  /** INBOX messages received at or after [since] (all when null), newest first. */
  fun listInbox(since: Instant? = null): List<MailboxMessage> =
    withFolder(inbox(), Folder.READ_ONLY, "list the inbox") { folder ->
      val messages = if (since == null) folder.messages else folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)))
      folder.fetch(messages, FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE)
        add(UIDFolder.FetchProfileItem.UID)
      })
      val uidValidity = folder.uidValidity
      messages.map { message ->
        val uid = folder.getUID(message)
        val receivedAt = receivedAt(message)
        val sender = (message.from?.firstOrNull() as? InternetAddress)?.address
        MailboxMessage(
            uid, uidValidity,
            MessageIds.normalise((message as MimeMessage).messageID)
                ?: MessageIds.generated(receivedAt, sender, message.subject, uidValidity, uid),
            receivedAt, message.subject, sender
        )
      }
          // IMAP SEARCH SINCE only compares dates
          .filter { since == null || !it.receivedAt.isBefore(since) }
          .sortedWith(compareByDescending<MailboxMessage> { it.receivedAt }.thenByDescending { it.uid })
    }

  /** The message with [uid] in [folder], parsed. */
  fun fetch(folder: String, uid: Long): ParsedMessage =
    withFolder(folder, Folder.READ_ONLY, "read message $uid") { imap ->
      parse(imap, message(imap, uid), uid)
    }

  /** Saves an attachment's decoded content to [target] and returns its size in bytes. */
  fun saveAttachment(folder: String, uid: Long, partPath: String, target: Path): Long =
    withFolder(folder, Folder.READ_ONLY, "save an attachment of message $uid") { imap ->
      val part = partAt(message(imap, uid), partPath)
      try {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        part.inputStream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
      } catch (e: IOException) {
        throw MailboxException("The attachment could not be saved to $target: ${e.message}", e)
      }
    }

  /** Moves the message to [destination], which must exist. */
  fun move(folder: String, uid: Long, destination: String) {
    if (folder == destination) {
      return
    }
    withFolder(folder, Folder.READ_WRITE, "move message $uid to $destination") { imap ->
      val target = imap.store.getFolder(destination)
      if (!target.exists()) {
        throw MailboxException("There is no folder named '$destination'")
      }
      val message = message(imap, uid)
      if ((imap.store as IMAPStore).hasCapability("MOVE")) {
        imap.moveMessages(arrayOf(message), target)
      } else {
        imap.copyMessages(arrayOf(message), target)
        message.setFlag(Flags.Flag.DELETED, true)
        imap.expunge(arrayOf(message))
      }
    }
  }

  /** Deletes the message by moving it to the trash folder (CLARIFICATIONS.md Q6). */
  fun delete(folder: String, uid: Long) {
    move(folder, uid, required(SettingKeys.IMAP_TRASH_FOLDER))
  }

  /** Marks the message as read or unread. */
  fun setRead(folder: String, uid: Long, read: Boolean) {
    withFolder(folder, Folder.READ_WRITE, "mark message $uid as ${if (read) "read" else "unread"}") { imap ->
      message(imap, uid).setFlag(Flags.Flag.SEEN, read)
    }
  }

  /** The full names of the folders that hold messages, sorted. */
  fun listFolders(): List<String> =
    withStore("list the folders") { store ->
      store.defaultFolder.list("*").filter { it.type and Folder.HOLDS_MESSAGES != 0 }.map { it.fullName }.sorted()
    }

  /** Forwards the message to [to] as an inline forward with its attachments (CLARIFICATIONS.md Q7). */
  fun forward(folder: String, uid: Long, to: String) {
    val recipient = try {
      InternetAddress(to.trim(), true)
    } catch (e: AddressException) {
      throw MailboxException("'$to' is not an email address")
    }
    val from = settings.getString(SettingKeys.SMTP_FROM_ADDRESS)?.takeIf { it.isNotBlank() }
        ?: settings.getString(SettingKeys.IMAP_USERNAME)?.takeIf { it.isNotBlank() }
        ?: throw MailboxException("Set the forwarding from address (smtp.from_address) to forward email")
    val session = smtpSession()
    val forward = withFolder(folder, Folder.READ_ONLY, "read message $uid to forward it") { imap ->
      val original = message(imap, uid)
      val parsed = parse(imap, original, uid)
      buildForward(session, original, parsed, from, recipient)
    }
    try {
      val username = settings.getString(SettingKeys.SMTP_USERNAME)?.takeIf { it.isNotBlank() }
      if (username == null) {
        Transport.send(forward)
      } else {
        Transport.send(forward, username, settings.getString(SettingKeys.SMTP_PASSWORD).orEmpty())
      }
    } catch (e: AuthenticationFailedException) {
      throw MailboxException("The SMTP server rejected the username or password", e)
    } catch (e: MessagingException) {
      throw MailboxException("The email could not be forwarded to $to: ${e.message}", e)
    }
  }

  // ---------------------------------------------------------------------------------------------

  private fun buildForward(session: Session, original: Message, parsed: ParsedMessage, from: String, to: InternetAddress): MimeMessage {
    val date = original.getHeader("Date")?.firstOrNull() ?: original.sentDate?.toString().orEmpty()
    val sender = listOfNotNull(parsed.senderName, parsed.sender?.let { "<$it>" }).joinToString(" ")
    val header = listOf(
        "---------- Forwarded message ---------",
        "From: $sender",
        "Date: $date",
        "Subject: ${parsed.subject.orEmpty()}",
        "To: ${parsed.recipients.joinToString(", ")}"
    )
    val alternative = MimeMultipart("alternative")
    alternative.addBodyPart(MimeBodyPart().apply {
      setText(header.joinToString("\n") + "\n\n" + parsed.text.orEmpty(), StandardCharsets.UTF_8.name())
    })
    parsed.html?.let { html ->
      val body = Jsoup.parse(html).body().html()
      val headerHtml = header.joinToString("<br>") { Entities.escape(it) }
      alternative.addBodyPart(MimeBodyPart().apply {
        setContent("<div>$headerHtml</div><br><blockquote>$body</blockquote>", "text/html; charset=UTF-8")
      })
    }
    val mixed = MimeMultipart("mixed")
    mixed.addBodyPart(MimeBodyPart().apply { setContent(alternative) })
    for (attachment in parsed.attachments) {
      val bytes = partAt(original, attachment.partPath).inputStream.use { it.readAllBytes() }
      mixed.addBodyPart(MimeBodyPart().apply {
        dataHandler = DataHandler(ByteArrayDataSource(bytes, attachment.contentType))
        fileName = attachment.filename
        disposition = Part.ATTACHMENT
        // Otherwise an all-ASCII file gets a text encoding, which turns its line endings into CRLF
        setHeader("Content-Transfer-Encoding", "base64")
      })
    }
    return MimeMessage(session).apply {
      setFrom(InternetAddress(from))
      setRecipient(Message.RecipientType.TO, to)
      setSubject("Fwd: " + parsed.subject.orEmpty(), StandardCharsets.UTF_8.name())
      sentDate = Date()
      setContent(mixed)
      saveChanges()
    }
  }

  private class Parts {
    var text: String? = null
    var html: String? = null
    val attachments = mutableListOf<AttachmentPart>()
  }

  private fun parse(folder: IMAPFolder, message: Message, uid: Long): ParsedMessage {
    val parts = Parts()
    walk(message, "", parts)
    val from = message.from?.firstOrNull() as? InternetAddress
    val receivedAt = receivedAt(message)
    val uidValidity = folder.uidValidity
    return ParsedMessage(
        uid = uid,
        uidValidity = uidValidity,
        folder = folder.fullName,
        messageId = MessageIds.normalise((message as MimeMessage).messageID)
            ?: MessageIds.generated(receivedAt, from?.address, message.subject, uidValidity, uid),
        receivedAt = receivedAt,
        subject = message.subject,
        sender = from?.address,
        senderName = from?.personal,
        recipients = message.getRecipients(Message.RecipientType.TO)?.map { it.toString() } ?: emptyList(),
        text = parts.text ?: parts.html?.let { HtmlParser.text(it) },
        html = parts.html,
        attachments = parts.attachments,
        isRead = message.isSet(Flags.Flag.SEEN)
    )
  }

  private fun walk(part: Part, path: String, parts: Parts) {
    if (part.isMimeType("multipart/*")) {
      val multipart = part.content as Multipart
      for (i in 0 until multipart.count) {
        walk(multipart.getBodyPart(i), if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}", parts)
      }
      return
    }
    val partPath = path.ifEmpty { "1" }
    val filename = part.fileName?.let { decode(it) }
    val disposition = part.disposition
    val attached = Part.ATTACHMENT.equals(disposition, ignoreCase = true)
    val contentId = (part as? MimePart)?.contentID
    // Embedded images (inline with a Content-ID) aren't attachments
    if ((filename != null || attached) && (contentId == null || attached)) {
      parts.attachments += AttachmentPart(
          filename ?: "attachment-$partPath", baseType(part.contentType), part.size.toLong(), partPath
      )
      return
    }
    when {
      part.isMimeType("text/plain") && parts.text == null -> parts.text = content(part)
      part.isMimeType("text/html") && parts.html == null -> parts.html = content(part)
    }
  }

  /** The part at a 1-based dotted [path], as walk() numbers them. */
  private fun partAt(message: Message, path: String): Part {
    var part: Part = message
    if (!message.isMimeType("multipart/*")) {
      if (path == "1") return message
      throw MailboxException("The message has no part $path")
    }
    for (index in path.split('.')) {
      val multipart = part.content as? Multipart ?: throw MailboxException("The message has no part $path")
      val i = index.toIntOrNull()?.minus(1)
      if (i == null || i < 0 || i >= multipart.count) {
        throw MailboxException("The message has no part $path")
      }
      part = multipart.getBodyPart(i)
    }
    return part
  }

  private fun content(part: Part): String =
    when (val content = part.content) {
      is String -> content
      else -> part.inputStream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
    }

  private fun decode(text: String): String =
    try {
      MimeUtility.decodeText(text)
    } catch (e: Exception) {
      text
    }

  private fun baseType(contentType: String?): String =
    contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null } ?: "application/octet-stream"

  private fun receivedAt(message: Message): Instant =
    (message.receivedDate ?: message.sentDate)?.toInstant() ?: Instant.EPOCH

  private fun message(folder: IMAPFolder, uid: Long): Message =
    folder.getMessageByUID(uid) ?: throw MailboxException("There is no message $uid in ${folder.fullName}")

  private fun inbox(): String = required(SettingKeys.IMAP_INBOX_FOLDER)

  private fun required(key: String): String =
    settings.getString(key)?.takeIf { it.isNotBlank() } ?: throw MailboxException("The ${settings.get(key).label} setting isn't set")

  private fun <T> withFolder(name: String, mode: Int, action: String, block: (IMAPFolder) -> T): T =
    withStore(action) { store ->
      val folder = store.getFolder(name) as IMAPFolder
      if (!folder.exists()) {
        throw MailboxException("There is no folder named '$name'")
      }
      folder.open(mode)
      try {
        block(folder)
      } finally {
        if (folder.isOpen) {
          folder.close(false)
        }
      }
    }

  private fun <T> withStore(action: String, block: (Store) -> T): T {
    val host = required(SettingKeys.IMAP_HOST)
    val username = required(SettingKeys.IMAP_USERNAME)
    val security = settings.getString(SettingKeys.IMAP_SECURITY) ?: "none"
    val protocol = if (security == "tls") "imaps" else "imap"
    val properties = Properties().apply {
      put("mail.store.protocol", protocol)
      put("mail.$protocol.host", host)
      put("mail.$protocol.port", settings.getInt(SettingKeys.IMAP_PORT).toString())
      put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT)
      put("mail.$protocol.timeout", READ_TIMEOUT)
      put("mail.$protocol.writetimeout", READ_TIMEOUT)
      // BODY.PEEK: reading a message doesn't set \Seen
      put("mail.$protocol.peek", "true")
      if (security == "starttls") {
        put("mail.imap.starttls.enable", "true")
        put("mail.imap.starttls.required", "true")
      }
    }
    val store = Session.getInstance(properties).getStore(protocol)
    try {
      store.connect(host, username, settings.getString(SettingKeys.IMAP_PASSWORD).orEmpty())
    } catch (e: AuthenticationFailedException) {
      throw MailboxException("Dovecot rejected the username or password", e)
    } catch (e: MessagingException) {
      throw MailboxException("Dovecot ($host) could not be reached: ${e.message}", e)
    }
    try {
      return block(store)
    } catch (e: MailboxException) {
      throw e
    } catch (e: MessagingException) {
      throw MailboxException("Could not $action: ${e.message}", e)
    } catch (e: IOException) {
      throw MailboxException("Could not $action: ${e.message}", e)
    } finally {
      runCatching { store.close() }
    }
  }

  private fun smtpSession(): Session {
    val host = settings.getString(SettingKeys.SMTP_HOST)?.takeIf { it.isNotBlank() }
        ?: throw MailboxException("The SMTP server address setting isn't set")
    val security = settings.getString(SettingKeys.SMTP_SECURITY) ?: "none"
    val protocol = if (security == "tls") "smtps" else "smtp"
    val properties = Properties().apply {
      put("mail.transport.protocol", protocol)
      put("mail.$protocol.host", host)
      put("mail.$protocol.port", settings.getInt(SettingKeys.SMTP_PORT).toString())
      put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT)
      put("mail.$protocol.timeout", READ_TIMEOUT)
      put("mail.$protocol.writetimeout", READ_TIMEOUT)
      put("mail.$protocol.auth", (!settings.getString(SettingKeys.SMTP_USERNAME).isNullOrBlank()).toString())
      if (security == "starttls") {
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
      }
    }
    return Session.getInstance(properties)
  }

  companion object {
    private const val CONNECT_TIMEOUT = "20000"
    private const val READ_TIMEOUT = "60000"
  }
}
