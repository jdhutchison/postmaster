package sh.hutch.postmaster.services

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.EmailTracking
import sh.hutch.postmaster.models.MessageRef
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.util.Mdc
import sh.hutch.postmaster.util.Times

/** What a tracking check found. */
data class TrackingResult(val checked: Int, val moved: Int, val deleted: Int)

/**
 * Notices emails that have been moved or deleted in the mailbox (CLARIFICATIONS.md R9). Tracked
 * emails received within tracking.window are looked for in their folder; one that isn't there is
 * looked for in the trash folder, and is then deleted if it's there and moved if it isn't. Either
 * way it's no longer tracked.
 */
@ApplicationScoped
class TrackingService(
    private val mailbox: MailboxClient,
    private val settings: SettingsService,
    private val emails: EmailRepository
) {

  /** An email to look for, and the folder it was in when the check started. */
  private class Tracked(val id: Long, val folder: String, val ref: MessageRef)

  /**
   * Looks for the tracked emails. If the mailbox can't be reached, nothing is changed.
   *
   * @throws MailboxException if the mailbox can't be reached
   */
  fun check(): TrackingResult {
    val now = Times.now()
    val (tracked, trash) = QuarkusTransaction.requiringNew().call {
      val since = now.minus(settings.getDuration(SettingKeys.TRACKING_WINDOW))
      val list = emails.listToTrack(since).mapNotNull { email ->
        email.folder?.let { Tracked(email.id!!, it, MessageRef(email.messageId, email.imapUid, email.uidValidity)) }
      }
      list to settings.getString(SettingKeys.IMAP_TRASH_FOLDER)?.takeIf { it.isNotBlank() }
    }
    if (tracked.isEmpty()) {
      return TrackingResult(0, 0, 0)
    }
    // Every folder is looked in before anything is saved, so a mailbox that goes away part way
    // changes nothing
    val missing = mutableListOf<Tracked>()
    val inTrash = mutableListOf<Tracked>()
    for ((folder, group) in tracked.groupBy { it.folder }) {
      val present = mailbox.present(folder, group.map { it.ref })
      for (email in group) {
        when {
          email.ref.messageId !in present -> missing += email
          folder == trash -> inTrash += email
        }
      }
    }
    val trashed = if (trash == null || missing.isEmpty()) emptySet() else mailbox.present(trash, missing.map { it.ref })
    val changes = missing.associate { it.id to if (it.ref.messageId in trashed) EmailTracking.DELETED else EmailTracking.MOVED } +
        inTrash.associate { it.id to EmailTracking.DELETED }
    var moved = 0
    var deleted = 0
    QuarkusTransaction.requiringNew().run {
      for (item in tracked) {
        val email = emails.findById(item.id) ?: continue
        // Left alone if it was moved, processed again or untracked while it was looked for
        if (email.folder != item.folder || !email.tracking.isTracked || email.deleted || email.status == EmailStatus.PENDING) {
          continue
        }
        email.trackingCheckedAt = now
        val state = changes[item.id] ?: continue
        email.changeTracking(state, now)
        if (state == EmailTracking.DELETED) {
          if (trash != null && email.folder != trash) {
            email.folder = trash
            email.imapUid = null
            email.uidValidity = null
          }
          deleted++
        } else {
          moved++
        }
        Mdc.with(Mdc.EMAIL to email.id) {
          LOG.infof("No longer tracked: %s", if (state == EmailTracking.DELETED) "it's in $trash" else "it's no longer in ${item.folder}")
        }
      }
    }
    LOG.infof("Tracking check: %d emails looked for, %d moved, %d deleted", tracked.size, moved, deleted)
    return TrackingResult(tracked.size, moved, deleted)
  }

  companion object {
    private val LOG = Logger.getLogger(TrackingService::class.java)
  }
}
