package sh.hutch.postmaster.actions

import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailActionResultEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailErrorEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.EmailFacts
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.ParsedMessage
import sh.hutch.postmaster.services.RuleEngine
import sh.hutch.postmaster.util.Times

/** When an action runs relative to the others; stages run in this order (CLARIFICATIONS.md Q12). */
enum class ActionStage {
  /** Changes to the email record only */
  RECORD,

  /** Fetching, the LLM, downloads, events and bills */
  GATHER,
  FORWARD,

  /** Marking read or unread */
  READ_STATE,

  /** Moving or deleting, last */
  MOVE
}

/** What an action did. */
data class ActionOutcome(val status: ActionResultStatus, val detail: String?) {
  companion object {
    fun done(detail: String? = null) = ActionOutcome(ActionResultStatus.DONE, detail)

    fun skipped(detail: String) = ActionOutcome(ActionResultStatus.SKIPPED, detail)

    fun failed(detail: String) = ActionOutcome(ActionResultStatus.FAILED, detail)
  }
}

/**
 * Carries out one type of action. Implement it as an @ApplicationScoped bean to add an action type;
 * the registry finds every handler.
 */
interface ActionHandler {
  /** The action type, as stored in `Rule_actions.action_type`. */
  val actionType: String

  val stage: ActionStage

  /** Order within the stage, lowest first. */
  val order: Int
    get() = 0

  /** Carries out [action] on the email; throwing records the action as failed. */
  fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome
}

/** Every action handler, by action type. */
@ApplicationScoped
class ActionRegistry(@All handlers: MutableList<ActionHandler>) {
  private val byType: Map<String, ActionHandler> = handlers.associateBy { it.actionType }

  fun handler(actionType: String): ActionHandler? = byType[actionType]

  fun types(): Set<String> = byType.keys
}

/** The email being processed, the message it came from, and whether processing was stopped. */
class ProcessingContext(val email: EmailEntity, val message: ParsedMessage?) {
  var stopped: Boolean = false
    private set

  /** Stops processing: nothing further runs (skip_processing). */
  fun stop() {
    stopped = true
  }

  fun facts(): EmailFacts = RuleEngine.factsOf(email)

  fun addError(phase: ErrorPhase, message: String) {
    email.errors.add(EmailErrorEntity().also {
      it.email = email
      it.phase = phase
      it.message = message.take(MAX_ERROR)
      it.createdAt = Times.now()
    })
  }

  internal fun record(rule: RuleEntity?, action: RuleActionEntity?, actionType: String, outcome: ActionOutcome) {
    email.actionResults.add(EmailActionResultEntity().also {
      it.email = email
      it.rule = rule
      it.ruleAction = action
      it.actionType = actionType
      it.source = ActionSource.RULE
      it.status = outcome.status
      it.detail = outcome.detail?.take(MAX_ERROR)
      it.executedAt = Times.now()
    })
  }

  companion object {
    private const val MAX_ERROR = 4000
  }
}

/** An action from a matching rule, waiting to run. [ruleOrder] is the rule's place among the matches. */
class QueuedAction(
    val rule: RuleEntity,
    val action: RuleActionEntity,
    val handler: ActionHandler?,
    val ruleOrder: Int,
    val phase: ErrorPhase
)

/** Runs actions, in the agreed order and with conflicts resolved, and records each outcome. */
@ApplicationScoped
class ActionExecutor(private val registry: ActionRegistry) {

  /** The enabled actions of [rule], ready to run. */
  fun actionsOf(rule: RuleEntity, ruleOrder: Int, phase: ErrorPhase): List<QueuedAction> =
    rule.actions
        .filter { !it.deleted && it.enabled }
        .sortedWith(compareBy({ it.position }, { it.id }))
        .map { QueuedAction(rule, it, registry.handler(it.actionType), ruleOrder, phase) }

  /** Runs [item] now, unless processing has been stopped. */
  fun run(context: ProcessingContext, item: QueuedAction) {
    if (context.stopped) {
      record(context, item, ActionOutcome.skipped(STOPPED))
      return
    }
    val handler = item.handler
    val outcome = if (handler == null) {
      ActionOutcome.failed("Unknown action type '${item.action.actionType}'")
    } else {
      try {
        handler.execute(context, item.action)
      } catch (e: Exception) {
        ActionOutcome.failed(e.message ?: e.javaClass.simpleName)
      }
    }
    record(context, item, outcome)
  }

  /**
   * Runs the queued actions by stage, then handler order, then rule order. Only the first move or
   * delete, and the first mark read or unread, runs; the others conflict with it.
   */
  fun runQueue(context: ProcessingContext, queue: List<QueuedAction>) {
    // In the stages where actions conflict, rule priority decides which runs, so it comes first
    val ordered = queue.sortedWith(
        compareBy<QueuedAction>(
            { it.handler?.stage ?: ActionStage.RECORD },
            { if (it.handler?.stage?.let { stage -> stage in CONFLICTING } == true) it.ruleOrder else 0 },
            { it.handler?.order ?: 0 },
            { it.ruleOrder },
            { it.action.position }
        )
    )
    val winners = mutableMapOf<ActionStage, QueuedAction>()
    for (item in ordered) {
      val stage = item.handler?.stage
      if (stage != null && stage in CONFLICTING) {
        val winner = winners[stage]
        if (winner != null) {
          val detail = "Conflicts with ${winner.action.actionType} from rule ${winner.rule.name}"
          record(context, item, ActionOutcome.skipped(detail))
          context.addError(item.phase, "${item.action.actionType}: $detail")
          continue
        }
        if (!context.stopped) {
          winners[stage] = item
        }
      }
      run(context, item)
    }
  }

  private fun record(context: ProcessingContext, item: QueuedAction, outcome: ActionOutcome) {
    context.record(item.rule, item.action, item.action.actionType, outcome)
    if (outcome.status == ActionResultStatus.FAILED) {
      context.addError(item.phase, "${item.action.actionType}: ${outcome.detail}")
    }
  }

  companion object {
    const val STOPPED = "Processing was stopped by skip_processing"
    private val CONFLICTING = setOf(ActionStage.MOVE, ActionStage.READ_STATE)
  }
}
