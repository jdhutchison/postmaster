package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.services.MailboxClient
import sh.hutch.postmaster.util.Times

/** Saves the email's attachments of interest so they can be downloaded from the UI. */
@ApplicationScoped
class DownloadAttachmentsHandler(private val mailbox: MailboxClient, private val store: AttachmentStore) : ActionHandler {
  override val actionType = ActionTypes.DOWNLOAD_ATTACHMENTS
  override val stage = ActionStage.GATHER
  override val order = 30

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val pending = context.email.attachments.filter { it.storedPath == null && it.partPath != null }
    if (pending.isEmpty()) {
      return ActionOutcome.skipped("No attachments to download")
    }
    val (folder, uid) = context.email.location(mailbox)
    for (attachment in pending) {
      val relative = store.newFile(context.email, attachment.filename)
      mailbox.saveAttachment(folder, uid, attachment.partPath!!, store.resolve(relative))
      attachment.storedPath = relative
      attachment.downloadedAt = Times.now()
    }
    return ActionOutcome.done("Downloaded ${pending.joinToString(", ") { it.filename }}")
  }
}
