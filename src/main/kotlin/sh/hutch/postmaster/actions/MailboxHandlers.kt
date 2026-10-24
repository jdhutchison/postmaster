package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.services.MailboxClient
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService

// Actions that change the message in the mailbox

/** The email's folder and UID, or null with the reason if it can't be found in the mailbox. */
internal fun EmailEntity.location(mailbox: MailboxClient): Pair<String, Long> {
  val folder = folder ?: throw IllegalStateException("The email's folder isn't known")
  val uid = imapUid ?: mailbox.findUid(folder, messageId) ?: throw IllegalStateException("The message can't be found in $folder")
  return folder to uid
}

/** Records that the message is now in [destination], finding its new UID there. */
internal fun EmailEntity.movedTo(destination: String, mailbox: MailboxClient) {
  folder = destination
  movedTo = destination
  imapUid = runCatching { mailbox.findUid(destination, messageId) }.getOrNull()
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
    val (folder, uid) = context.email.location(mailbox)
    mailbox.move(folder, uid, destination)
    context.email.movedTo(destination, mailbox)
    return ActionOutcome.done("Moved to $destination")
  }
}

@ApplicationScoped
class DeleteHandler(private val mailbox: MailboxClient, private val settings: SettingsService) : ActionHandler {
  override val actionType = ActionTypes.DELETE
  override val stage = ActionStage.MOVE
  override val order = 1

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val (folder, uid) = context.email.location(mailbox)
    mailbox.delete(folder, uid)
    val trash = settings.getString(SettingKeys.IMAP_TRASH_FOLDER).orEmpty()
    context.email.movedTo(trash, mailbox)
    return ActionOutcome.done("Moved to $trash")
  }
}

@ApplicationScoped
class ForwardEmailHandler(private val mailbox: MailboxClient) : ActionHandler {
  override val actionType = ActionTypes.FORWARD_EMAIL
  override val stage = ActionStage.FORWARD

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val to = action.destination?.takeIf { it.isNotBlank() } ?: return ActionOutcome.failed("No address to forward to")
    val (folder, uid) = context.email.location(mailbox)
    mailbox.forward(folder, uid, to)
    return ActionOutcome.done("Forwarded to $to")
  }
}
