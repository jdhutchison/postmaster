package sh.hutch.postmaster.services

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.entities.FolderEntity
import sh.hutch.postmaster.repositories.FolderRepository
import sh.hutch.postmaster.util.Times
import java.time.Instant

/**
 * The mailbox's folders, as last scanned, for choosing where to move email (CLARIFICATIONS.md R9).
 * They're scanned every imap.folder_scan_frequency, and again on request.
 */
@ApplicationScoped
class FolderService(private val mailbox: MailboxClient, private val folders: FolderRepository) {

  /**
   * Reads the folders from the mailbox and saves them, replacing those saved before; returns them.
   *
   * @throws MailboxException if the mailbox can't be reached
   */
  fun scan(): List<String> {
    val names = mailbox.listFolders().distinct()
    val now = Times.now()
    QuarkusTransaction.requiringNew().run {
      val saved = folders.listAll().associateBy { it.name }
      for (name in names) {
        val folder = saved[name]
        if (folder == null) {
          folders.persist(FolderEntity().also {
            it.name = name
            it.scannedAt = now
          })
        } else {
          folder.scannedAt = now
        }
      }
      saved.values.filter { it.name !in names }.forEach { folders.delete(it) }
    }
    LOG.infof("Found %d mailbox folders", names.size)
    return names.sorted()
  }

  /** The folders as last scanned, sorted; if they've never been scanned, they're scanned now. */
  fun names(): List<String> {
    val saved = QuarkusTransaction.requiringNew().call { folders.listByName().map { it.name } }
    return saved.ifEmpty { scan() }
  }

  /** The folders as last scanned, sorted, without scanning: empty if they've never been scanned. */
  fun saved(): List<String> = QuarkusTransaction.requiringNew().call { folders.listByName().map { it.name } }

  /** When the folders were last scanned; null if never. */
  fun lastScannedAt(): Instant? =
    QuarkusTransaction.requiringNew().call { folders.listAll().mapNotNull { it.scannedAt }.maxOrNull() }

  companion object {
    private val LOG = Logger.getLogger(FolderService::class.java)
  }
}
