package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes

// Actions that only change the email record

@ApplicationScoped
class SetTypeHandler : ActionHandler {
  override val actionType = ActionTypes.SET_TYPE
  override val stage = ActionStage.RECORD

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val type = action.type?.takeIf { !it.deleted } ?: return ActionOutcome.failed("The action's type no longer exists")
    context.email.assignType(type)
    return ActionOutcome.done("Type set to ${type.name}")
  }
}

@ApplicationScoped
class SetReplyNeededHandler : ActionHandler {
  override val actionType = ActionTypes.SET_REPLY_NEEDED
  override val stage = ActionStage.RECORD

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    context.email.responseRequired = true
    return ActionOutcome.done("Reply needed")
  }
}

@ApplicationScoped
class SkipProcessingHandler : ActionHandler {
  override val actionType = ActionTypes.SKIP_PROCESSING
  override val stage = ActionStage.RECORD

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    context.stop()
    return ActionOutcome.done("Processing stopped")
  }
}
