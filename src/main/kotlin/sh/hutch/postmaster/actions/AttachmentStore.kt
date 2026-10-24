package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailAttachmentEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where downloaded attachments and bills are kept: `<attachments.path>/<yyyy>/<MM>/<email id>/`.
 * Stored paths are relative to attachments.path.
 */
@ApplicationScoped
class AttachmentStore(private val settings: SettingsService) {

  fun root(): Path = Path.of(settings.getString(SettingKeys.ATTACHMENTS_PATH) ?: throw IllegalStateException("The attachments path isn't set"))

  /** A relative path for a new file of [email] named [filename], not used by any existing file. */
  fun newFile(email: EmailEntity, filename: String): String {
    val received = (email.receivedAt ?: email.createdAt)!!.atOffset(ZoneOffset.UTC)
    val dir = MONTH.format(received) + "/" + email.id
    val safe = sanitise(filename)
    val base = safe.substringBeforeLast('.', safe)
    val extension = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
    var candidate = "$dir/$safe"
    var n = 2
    while (Files.exists(root().resolve(candidate))) {
      candidate = "$dir/$base-$n$extension"
      n++
    }
    return candidate
  }

  fun resolve(relative: String): Path = root().resolve(relative)

  /** A record for a downloaded file that wasn't an attachment of the message, such as a bill. */
  fun addDownloaded(email: EmailEntity, filename: String, relative: String, contentType: String?, size: Long): EmailAttachmentEntity {
    val attachment = EmailAttachmentEntity().also {
      it.email = email
      it.filename = filename
      it.contentType = contentType
      it.sizeBytes = size
      it.storedPath = relative
      it.downloadedAt = sh.hutch.postmaster.util.Times.now()
    }
    email.attachments.add(attachment)
    return attachment
  }

  companion object {
    private val MONTH = DateTimeFormatter.ofPattern("yyyy/MM")

    /** A safe file name: no directories, control characters or leading dots, at most 150 characters. */
    fun sanitise(filename: String): String {
      val name = filename.substringAfterLast('/').substringAfterLast('\\')
          .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_")
          .trim().trimStart('.')
      val cleaned = name.ifEmpty { "attachment" }
      return if (cleaned.length > 150) cleaned.takeLast(150) else cleaned
    }
  }
}
