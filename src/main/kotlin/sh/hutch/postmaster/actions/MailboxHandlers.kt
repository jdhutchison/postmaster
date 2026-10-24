package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.services.MailboxClient
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService

// Actions that change the message in the mailbox

/**
 * The email's folder and UID. The stored UID is used only while the folder's UIDVALIDITY is
 * unchanged; otherwise the message is found again by its Message-ID and the new UID is stored.
 *
 * @throws IllegalStateException if the message can't be found in the mailbox
 */
internal fun EmailEntity.location(mailbox: MailboxClient): Pair<String, Long> {
  val folder = folder ?: throw IllegalStateException("The email's folder isn't known")
  val found = mailbox.locate(folder, messageId, imapUid, uidValidity) ?: throw IllegalStateException("The message can't be found in $folder")
  imapUid = found.uid
  uidValidity = found.uidValidity
  return folder to found.uid
}

/**
 * Moves the message to [destination]. If it isn't in its folder but is already in [destination], an
 * earlier, interrupted attempt moved it, and that's recorded instead (CLARIFICATIONS.md R6).
 *
 * @throws IllegalStateException if the message can't be found
 */
internal fun EmailEntity.moveTo(destination: String, mailbox: MailboxClient) {
  val current = folder ?: throw IllegalStateException("The email's folder isn't known")
  val found = mailbox.locate(current, messageId, imapUid, uidValidity)
  if (found == null) {
    if (current != destination && mailbox.locate(destination, messageId) != null) {
      movedTo(destination, mailbox)
      return
    }
    throw IllegalStateException("The message can't be found in $current")
  }
  mailbox.move(current, found.uid, destination)
  movedTo(destination, mailbox)
}

/** Records that the message is now in [destination], finding its new UID there. */
internal fun EmailEntity.movedTo(destination: String, mailbox: MailboxClient) {
  folder = destination
  movedTo = destination
  val found = runCatching { mailbox.locate(destination, messageId) }.getOrNull()
  imapUid = found?.uid
  uidValidity = found?.uidValidity
}

@ApplicationScoped
class MarkReadHandler(private val mailbox: MailboxClient) : ActionHandler {
  override val actionType = ActionTypes.MARK_READ
  override val stage = ActionStage.READ_STATE

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val (folder, uid) = context.email.location(mailbox)
    mailbox.setRead(folder, uid, true)
    context.email.isRead = true
    return ActionOutcome.done("Marked read")
  }
}

@ApplicationScoped
class MarkUnreadHandler(private val mailbox: MailboxClient) : ActionHandler {
  override val actionType = ActionTypes.MARK_UNREAD
  override val stage = ActionStage.READ_STATE

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val (folder, uid) = context.email.location(mailbox)
    mailbox.setRead(folder, uid, false)
    context.email.isRead = false
    return ActionOutcome.done("Marked unread")
  }
}

@ApplicationScoped
class MoveHandler(private val mailbox: MailboxClient) : ActionHandler {
  override val actionType = ActionTypes.MOVE
  override val stage = ActionStage.MOVE

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val destination = action.destination?.takeIf { it.isNotBlank() } ?: return ActionOutcome.failed("No folder to move to")
    context.email.moveTo(destination, mailbox)
    return ActionOutcome.done("Moved to $destination")
  }
}

@ApplicationScoped
class DeleteHandler(private val mailbox: MailboxClient, private val settings: SettingsService) : ActionHandler {
  override val actionType = ActionTypes.DELETE
  override val stage = ActionStage.MOVE
  override val order = 1

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val trash = settings.getString(SettingKeys.IMAP_TRASH_FOLDER)?.takeIf { it.isNotBlank() }
        ?: return ActionOutcome.failed("The trash folder (imap.trash_folder) isn't set")
    context.email.moveTo(trash, mailbox)
    return ActionOutcome.done("Moved to $trash")
  }
}

@ApplicationScoped
class ForwardEmailHandler(private val mailbox: MailboxClient) : ActionHandler {
  override val actionType = ActionTypes.FORWARD_EMAIL
  override val stage = ActionStage.FORWARD

  // A second forward would send the email twice
  override val repeatable = false

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val to = action.destination?.takeIf { it.isNotBlank() } ?: return ActionOutcome.failed("No address to forward to")
    val (folder, uid) = context.email.location(mailbox)
    mailbox.forward(folder, uid, to)
    return ActionOutcome.done("Forwarded to $to")
  }
}
