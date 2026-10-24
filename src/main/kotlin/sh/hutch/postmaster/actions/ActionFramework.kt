package sh.hutch.postmaster.actions

import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.entities.EmailActionResultEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailErrorEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.EmailFacts
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.Option
import sh.hutch.postmaster.models.ParsedMessage
import sh.hutch.postmaster.models.RuleOptions
import sh.hutch.postmaster.services.RuleEngine
import sh.hutch.postmaster.util.Mdc
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

  /** How the rule editor names the action, e.g. "Mark read". */
  val label: String
    get() = ActionRegistry.humanise(actionType)

  /**
   * Whether running the action again after an interruption is safe: it notices what an earlier,
   * interrupted run did (CLARIFICATIONS.md R6). One that isn't is recorded as started before it
   * runs, and an interrupted one is recorded as failed rather than run again.
   */
  val repeatable: Boolean
    get() = true

  /** Carries out [action] on the email; throwing records the action as failed. */
  fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome
}

/** Every action handler, by action type. */
@ApplicationScoped
class ActionRegistry(@All handlers: MutableList<ActionHandler>) {
  private val byType: Map<String, ActionHandler> = handlers.associateBy { it.actionType }

  fun handler(actionType: String): ActionHandler? = byType[actionType]

  fun types(): Set<String> = byType.keys

  /**
   * How an action type is named in the UI: the built-in labels, else the handler's label, else
   * the name made readable (`set_response_required` is "Set response required").
   */
  fun label(actionType: String): String =
    RuleOptions.ACTION_LABELS[actionType] ?: byType[actionType]?.label ?: humanise(actionType)

  /** Every action type for the rule editor: the built-in ones in their usual order, then the others by name. */
  fun options(): List<Option> {
    val builtIn = ActionTypes.BUILT_IN.filter { it in byType }
    val others = byType.keys.filter { it !in ActionTypes.BUILT_IN }.sorted()
    return (builtIn + others).map { Option(it, label(it)) }
  }

  companion object {
    /** An action type's name as words: `mark_read` is "Mark read". */
    fun humanise(actionType: String): String = actionType.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }
}

/** The email being processed, the message it came from, and whether processing was stopped. */
class ProcessingContext(val email: EmailEntity, val message: ParsedMessage?) {
  var stopped: Boolean = false
    private set

  /**
   * Whether the LLM has found events in this pass. Reprocessing keeps events already added to a
   * calendar, so whether the email has events doesn't say whether they've been looked for.
   */
  var eventsFound: Boolean = false

  /** Stops processing: nothing further runs (skip_processing). */
  fun stop() {
    stopped = true
  }

  /** The result recorded for [item] in this or an earlier pass, if it has one. */
  fun resultFor(item: QueuedAction): EmailActionResultEntity? =
    email.actionResults.lastOrNull { it.source == ActionSource.RULE && it.ruleAction?.id == item.action.id && it.rule?.id == item.rule.id }

  fun facts(): EmailFacts = RuleEngine.factsOf(email)

  fun addError(phase: ErrorPhase, message: String) {
    LOG.warnf("Error (%s): %s", phase.code, message)
    email.errors.add(EmailErrorEntity().also {
      it.email = email
      it.phase = phase
      it.message = message.take(MAX_ERROR)
      it.createdAt = Times.now()
    })
  }

  /** Records what [action] did, completing its started result if it has one. */
  internal fun record(rule: RuleEntity?, action: RuleActionEntity?, actionType: String, outcome: ActionOutcome) {
    val started = email.actionResults.lastOrNull {
      it.status == ActionResultStatus.STARTED && it.source == ActionSource.RULE && action != null && it.ruleAction?.id == action.id && it.rule?.id == rule?.id
    }
    val result = started ?: EmailActionResultEntity().also {
      it.email = email
      it.rule = rule
      it.ruleAction = action
      it.actionType = actionType
      it.source = ActionSource.RULE
      email.actionResults.add(it)
    }
    result.status = outcome.status
    result.detail = outcome.detail?.take(MAX_ERROR)
    result.executedAt = Times.now()
  }

  companion object {
    private val LOG = Logger.getLogger(ProcessingContext::class.java)
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
  fun run(context: ProcessingContext, item: QueuedAction) = Mdc.with(Mdc.RULE to item.rule.name, Mdc.ACTION to item.action.actionType) {
    execute(context, item)
  }

  private fun execute(context: ProcessingContext, item: QueuedAction) {
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
   * The queued actions in the order they run: by stage, then handler order, then rule order. In the
   * stages where actions conflict, rule priority comes first, because it decides which one runs.
   */
  fun ordered(queue: List<QueuedAction>): List<QueuedAction> =
    queue.sortedWith(
        compareBy<QueuedAction>(
            { it.handler?.stage ?: ActionStage.RECORD },
            { if (it.handler?.stage?.let { stage -> stage in CONFLICTING } == true) it.ruleOrder else 0 },
            { it.handler?.order ?: 0 },
            { it.ruleOrder },
            { it.action.position }
        )
    )

  /**
   * The next action in [ordered] to run, working from the results already recorded, so processing
   * can carry on after an interruption (CLARIFICATIONS.md R6). On the way it records the actions
   * that won't run: everything after skip_processing, actions that conflict with an earlier move or
   * read state change (only the first of each runs), and actions that can't be repeated that were
   * started by an interrupted pass ([startedNow] holds those started by this one). Null when every
   * action has a result.
   */
  fun next(context: ProcessingContext, ordered: List<QueuedAction>, startedNow: Set<Long>): QueuedAction? {
    val winners = mutableMapOf<ActionStage, QueuedAction>()
    for (item in ordered) {
      val stage = item.handler?.stage
      val conflicting = stage != null && stage in CONFLICTING
      val result = context.resultFor(item)
      if (result != null && result.status != ActionResultStatus.STARTED) {
        if (conflicting && ran(result)) {
          winners.putIfAbsent(stage, item)
        }
        continue
      }
      if (result != null && item.handler?.repeatable == false && item.action.id !in startedNow) {
        Mdc.with(Mdc.RULE to item.rule.name, Mdc.ACTION to item.action.actionType) {
          record(context, item, ActionOutcome.failed(INTERRUPTED))
        }
        if (conflicting) {
          winners.putIfAbsent(stage, item)
        }
        continue
      }
      if (context.stopped) {
        Mdc.with(Mdc.RULE to item.rule.name, Mdc.ACTION to item.action.actionType) { record(context, item, ActionOutcome.skipped(STOPPED)) }
        continue
      }
      if (conflicting) {
        val winner = winners[stage]
        if (winner != null) {
          val detail = "Conflicts with ${winner.action.actionType} from rule ${winner.rule.name}"
          Mdc.with(Mdc.RULE to item.rule.name, Mdc.ACTION to item.action.actionType) {
            record(context, item, ActionOutcome.skipped(detail))
            context.addError(item.phase, "${item.action.actionType}: $detail")
          }
          continue
        }
        winners[stage] = item
      }
      return item
    }
    return null
  }

  /** Records [item] as started, before an action that can't be repeated runs. */
  fun markStarted(context: ProcessingContext, item: QueuedAction) =
    Mdc.with(Mdc.RULE to item.rule.name, Mdc.ACTION to item.action.actionType) {
      LOG.info("Action started")
      context.record(item.rule, item.action, item.action.actionType, ActionOutcome(ActionResultStatus.STARTED, null))
    }

  /** Whether [result] is from an action that ran, rather than one skipped for a conflict or after skip_processing. */
  private fun ran(result: EmailActionResultEntity): Boolean =
    result.status != ActionResultStatus.SKIPPED || (result.detail != STOPPED && result.detail?.startsWith("Conflicts with ") != true)

  private fun record(context: ProcessingContext, item: QueuedAction, outcome: ActionOutcome) {
    if (outcome.status != ActionResultStatus.FAILED) {
      // Failures are logged as errors below
      LOG.infof("Action %s: %s", outcome.status.code, outcome.detail.orEmpty())
    }
    context.record(item.rule, item.action, item.action.actionType, outcome)
    if (outcome.status == ActionResultStatus.FAILED) {
      context.addError(item.phase, "${item.action.actionType}: ${outcome.detail}")
    }
  }

  companion object {
    private val LOG = Logger.getLogger(ActionExecutor::class.java)
    const val STOPPED = "Processing was stopped by skip_processing"
    const val INTERRUPTED = "Processing was interrupted while this ran, so it may or may not have happened; check before running it again"
    private val CONFLICTING = setOf(ActionStage.MOVE, ActionStage.READ_STATE)
  }
}
