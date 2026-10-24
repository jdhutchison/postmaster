package sh.hutch.postmaster.services

import com.fasterxml.jackson.databind.JsonNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import sh.hutch.postmaster.actions.ActionExecutor
import sh.hutch.postmaster.actions.ActionStage
import sh.hutch.postmaster.actions.EventFinder
import sh.hutch.postmaster.actions.ProcessingContext
import sh.hutch.postmaster.actions.QueuedAction
import sh.hutch.postmaster.entities.EmailAttachmentEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailRuleMatchEntity
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.MailboxMessage
import sh.hutch.postmaster.models.ParsedMessage
import sh.hutch.postmaster.models.ProcessingProgress
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.util.Mdc
import sh.hutch.postmaster.util.Times
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Processes email (REQUIREMENTS.md "Processing emails"): fetches new INBOX messages, classifies
 * them with rules and then the LLM, and applies the action rules. One run at a time; each email is
 * processed in its own transaction.
 */
@ApplicationScoped
class ProcessingService(
    private val mailbox: MailboxClient,
    private val settings: SettingsService,
    private val emails: EmailRepository,
    private val runs: ProcessingRunRepository,
    private val rules: RuleRepository,
    private val types: TypeRepository,
    private val engine: RuleEngine,
    private val executor: ActionExecutor,
    private val llm: LlmClient,
    private val prompts: PromptRenderer,
    private val events: EventFinder,
    private val todos: TodoListService,
    private val workers: ManagedExecutor
) {
  private val running = AtomicBoolean(false)

  /** Set when the application is stopping: no further step or run starts. */
  private val stopping = AtomicBoolean(false)

  /** Emails being processed now, so each is processed by one pass at a time. */
  private val active = ConcurrentHashMap.newKeySet<Long>()

  /** Whether a run is in progress. */
  val isRunning: Boolean
    get() = running.get()

  /** Runs processing now and returns the run, or null if a run is already in progress. */
  fun runNow(trigger: RunTrigger): ProcessingRunEntity? {
    if (stopping.get() || !running.compareAndSet(false, true)) {
      return null
    }
    try {
      return doRun(trigger)
    } finally {
      running.set(false)
    }
  }

  /** Starts a manual run in the background; false if a run is already in progress. */
  fun runNowAsync(): Boolean {
    if (stopping.get() || !running.compareAndSet(false, true)) {
      return false
    }
    try {
      workers.runAsync {
        try {
          doRun(RunTrigger.MANUAL)
        } catch (e: Exception) {
          LOG.error("Processing run failed", e)
        } finally {
          running.set(false)
        }
      }
    } catch (e: Exception) {
      running.set(false)
      throw e
    }
    return true
  }

  /**
   * Runs with the application's class loader as the thread's context class loader, so a run works
   * from any thread (e.g. the common fork-join pool), not just the threads Quarkus manages.
   */
  private fun doRun(trigger: RunTrigger): ProcessingRunEntity {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = ProcessingService::class.java.classLoader
    try {
      return run(trigger)
    } finally {
      thread.contextClassLoader = previous
    }
  }

  private fun run(trigger: RunTrigger): ProcessingRunEntity {
    val runId = QuarkusTransaction.requiringNew().call {
      val run = ProcessingRunEntity()
      run.triggerSource = trigger
      run.status = RunStatus.RUNNING
      run.startedAt = Times.now()
      runs.persist(run)
      run.id!!
    }
    return Mdc.with(Mdc.RUN to runId) { execute(runId, trigger) }
  }

  private fun execute(runId: Long, trigger: RunTrigger): ProcessingRunEntity {
    LOG.infof("Processing run started (%s)", trigger.code)
    var found = 0
    var processed = 0
    var failed = 0
    var waiting = 0
    var error: String? = null
    try {
      val done = todos.syncCompleted()
      if (done > 0) {
        LOG.infof("%d action item(s) were ticked off in the to-do list", done)
      }
    } catch (e: Exception) {
      LOG.warn("Checking the to-do list failed", e)
    }
    try {
      // Email left part-way, by an interruption or while the LLM was unavailable, carries on first (R6)
      for (id in QuarkusTransaction.requiringNew().call { emails.listPendingIds() }) {
        if (stopping.get()) {
          break
        }
        when (resume(id)) {
          Outcome.FINISHED -> processed++
          Outcome.DEFERRED -> waiting++
          Outcome.INTERRUPTED -> {}
        }
      }
      val listed = mailbox.listInbox(lookbackStart())
      val fresh = QuarkusTransaction.requiringNew().call { listed.filter { !emails.existsByMessageId(it.messageId) } }
      found = fresh.size
      LOG.infof("%d new of %d messages in the inbox", found, listed.size)
      for (message in fresh) {
        if (stopping.get()) {
          LOG.info("Stopping: the rest of the new email is left for the next run")
          break
        }
        try {
          when (process(message)) {
            Outcome.FINISHED -> processed++
            Outcome.DEFERRED -> waiting++
            Outcome.INTERRUPTED -> {}
          }
        } catch (e: Exception) {
          failed++
          LOG.warnf(e, "Processing message %s failed", message.messageId)
        }
      }
    } catch (e: Exception) {
      error = e.message ?: e.javaClass.simpleName
      LOG.warn("Processing run failed", e)
    }
    LOG.infof(
        "Processing run finished: %d found, %d processed, %d failed, %d waiting for the LLM%s",
        found, processed, failed, waiting, error?.let { "; $it" } ?: ""
    )
    return QuarkusTransaction.requiringNew().call {
      val run = runs.findById(runId)!!
      run.status = if (error == null) RunStatus.SUCCEEDED else RunStatus.FAILED
      run.error = error
      run.finishedAt = Times.now()
      run.emailsFound = found
      run.emailsProcessed = processed
      run.emailsFailed = failed
      run
    }
  }

  /**
   * Where the inbox listing starts: processing.lookback before the latest successful run started,
   * so email that arrived while Postmaster was down or failing is still found (R6).
   */
  private fun lookbackStart(): Instant = QuarkusTransaction.requiringNew().call {
    val now = Times.now()
    val from = runs.findLatestSucceeded()?.startedAt?.takeIf { it.isBefore(now) } ?: now
    from.minus(settings.getDuration(SettingKeys.PROCESSING_LOOKBACK))
  }

  /**
   * Fetches one listed INBOX message, saves it, then processes it. If it can't be fetched or saved,
   * nothing has been done with it and a later run tries again.
   */
  private fun process(listed: MailboxMessage): Outcome = Mdc.with(Mdc.MESSAGE_ID to listed.messageId) {
    val inbox = settings.getString(SettingKeys.IMAP_INBOX_FOLDER) ?: "INBOX"
    val message = mailbox.fetch(inbox, listed.uid)
    // Saved before anything is done with it, so it's never lost track of (R6)
    val id = QuarkusTransaction.requiringNew().call {
      val email = create(message)
      emails.persist(email)
      email.id!!
    }
    active.add(id)
    try {
      Mdc.with(Mdc.EMAIL to id) {
        LOG.infof("Processing '%s' from %s", message.subject, message.sender)
        drive(Pass(id, message))
      }
    } finally {
      active.remove(id)
    }
  }

  /** Carries on processing an email that an earlier pass left part-way. */
  private fun resume(emailId: Long): Outcome = Mdc.with(Mdc.EMAIL to emailId) {
    if (!active.add(emailId)) {
      // It's being reprocessed right now
      return@with Outcome.INTERRUPTED
    }
    try {
      LOG.info("Carrying on processing the email")
      drive(Pass(emailId, null))
    } catch (e: Exception) {
      LOG.warn("Carrying on processing the email failed", e)
      Outcome.INTERRUPTED
    } finally {
      active.remove(emailId)
    }
  }

  /**
   * Processes an email again, from its message in the mailbox. If the LLM isn't available it's left
   * pending, and the next run finishes it.
   *
   * @throws IllegalStateException if the email is already being processed
   */
  fun reprocess(emailId: Long): EmailEntity {
    if (!active.add(emailId)) {
      throw IllegalStateException("The email is already being processed")
    }
    try {
      val (folder, known, messageId) = QuarkusTransaction.requiringNew().call {
        val email = emails.findActive(emailId) ?: throw jakarta.ws.rs.NotFoundException("Email $emailId not found")
        Triple(email.folder ?: settings.getString(SettingKeys.IMAP_INBOX_FOLDER) ?: "INBOX", email.imapUid?.let { it to email.uidValidity }, email.messageId)
      }
      val found = mailbox.locate(folder, messageId, known?.first, known?.second) ?: throw MailboxException("The message can't be found in $folder")
      val message = mailbox.fetch(folder, found.uid)
      QuarkusTransaction.requiringNew().run {
        val email = emails.findActive(emailId)!!
        reset(email)
        email.imapUid = found.uid
        email.uidValidity = found.uidValidity
        email.folder = folder
      }
      Mdc.with(Mdc.EMAIL to emailId) {
        LOG.info("Reprocessing the email")
        drive(Pass(emailId, message))
      }
      return QuarkusTransaction.requiringNew().call { emails.findById(emailId)!! }
    } finally {
      active.remove(emailId)
    }
  }

  // ---------------------------------------------------------------------------------------------

  /** What a pass over an email carries from one step's transaction to the next. */
  private class Pass(val emailId: Long, val message: ParsedMessage?) {
    var eventsFound = false

    /** The actions that can't be repeated that this pass has recorded as started. */
    val started = mutableSetOf<Long>()
  }

  private enum class Outcome {
    /** Processing finished: the email is processed, skipped or failed. */
    FINISHED,

    /** The LLM couldn't be reached; the email waits for the next run. */
    DEFERRED,

    /** The application is stopping; the email is carried on with on the next run. */
    INTERRUPTED
  }

  /** The LLM couldn't be reached, so the email is left for the next run. */
  private class Deferred(cause: LlmException) : RuntimeException(cause)

  /**
   * Runs [block] in a transaction of its own, with the email as it is saved, and saves what it
   * changed: one step of processing (R6).
   */
  private fun <T> step(pass: Pass, block: (ProcessingContext) -> T): T =
    QuarkusTransaction.requiringNew().timeout(TRANSACTION_TIMEOUT).call {
      val email = emails.findById(pass.emailId) ?: throw IllegalStateException("Email ${pass.emailId} has gone")
      val context = ProcessingContext(email, pass.message)
      context.eventsFound = pass.eventsFound
      if (email.actionResults.any { it.source == ActionSource.RULE && it.actionType == ActionTypes.SKIP_PROCESSING && it.status == ActionResultStatus.DONE }) {
        context.stop()
      }
      val result = block(context)
      pass.eventsFound = context.eventsFound
      result
    }

  /**
   * Takes the email through the steps it hasn't done yet, saving after each (R6): the
   * classification rule, the LLM, the classification rule's actions that need the LLM, matching
   * the action rules, then the actions one at a time. An interruption loses at most the step it
   * was in, and the next run carries on from there; the action results show which actions have
   * run. An email that keeps failing is given up on after [MAX_ATTEMPTS] passes.
   */
  private fun drive(pass: Pass): Outcome {
    try {
      val (attempts, saved) = step(pass) { context ->
        val email = context.email
        email.attempts++
        val progress = email.progress ?: ProcessingProgress.RECORDED
        if (progress == ProcessingProgress.SUMMARISED || progress == ProcessingProgress.MATCHED) {
          // The summary was saved by an earlier pass
          pass.eventsFound = email.events.isNotEmpty()
        }
        email.attempts to progress
      }
      if (attempts > MAX_ATTEMPTS) {
        step(pass) { context ->
          context.addError(ErrorPhase.ACTION, "Processing was given up after $MAX_ATTEMPTS attempts; reprocess the email once the problem is fixed")
          finish(context.email, EmailStatus.FAILED)
        }
        return Outcome.FINISHED
      }
      var progress = saved
      if (progress == ProcessingProgress.RECORDED) {
        classifyByRules(pass)
        progress = ProcessingProgress.CLASSIFIED
      }
      if (!step(pass) { it.stopped }) {
        if (progress == ProcessingProgress.CLASSIFIED) {
          if (stopping.get()) {
            return Outcome.INTERRUPTED
          }
          if (!summarise(pass, attempts)) {
            return Outcome.DEFERRED
          }
          progress = ProcessingProgress.SUMMARISED
        }
        val afterSummary = Mdc.with(Mdc.PHASE to ErrorPhase.LLM.code) {
          runActions(pass) { it.phase == ErrorPhase.CLASSIFICATION && it.action.actionType in AFTER_SUMMARY }
        }
        if (!afterSummary) {
          return Outcome.INTERRUPTED
        }
        if (progress == ProcessingProgress.SUMMARISED) {
          matchActionRules(pass)
        }
      }
      if (!runActions(pass) { true }) {
        return Outcome.INTERRUPTED
      }
      step(pass) { finish(it.email, if (it.stopped) EmailStatus.SKIPPED else EmailStatus.PROCESSED) }
      return Outcome.FINISHED
    } catch (e: Exception) {
      LOG.warnf(e, "Processing failed")
      try {
        step(pass) { context ->
          context.addError(ErrorPhase.ACTION, "Processing failed: ${e.message ?: e.javaClass.simpleName}")
          finish(context.email, EmailStatus.FAILED)
        }
      } catch (unsaved: Exception) {
        // The email stays pending, so the next run carries on with it
        LOG.warn("The failure couldn't be saved", unsaved)
      }
      return Outcome.FINISHED
    }
  }

  /** The first matching classification rule, with its record actions run at once (Q12). */
  private fun classifyByRules(pass: Pass) = Mdc.with(Mdc.PHASE to ErrorPhase.CLASSIFICATION.code) {
    step(pass) { context ->
      val email = context.email
      val classifier = rules.listEnabled(RuleType.CLASSIFICATION).firstOrNull { engine.matches(it, context.facts()) }
      if (classifier == null) {
        LOG.info("No classification rule matched")
      } else {
        Mdc.with(Mdc.RULE to classifier.name) { LOG.info("Classification rule matched") }
        recordMatch(email, classifier, 0)
        executor.actionsOf(classifier, 0, ErrorPhase.CLASSIFICATION)
            .filter { it.handler?.stage == ActionStage.RECORD }
            .forEach { executor.run(context, it) }
      }
      email.progress = ProcessingProgress.CLASSIFIED
    }
  }

  /**
   * Asks the LLM to classify the email if it has no type yet, and to summarise it (Q15), then saves
   * the answers. No transaction is open while the LLM answers. Returns false, leaving the email for
   * the next run, if the LLM can't be reached and there are attempts left (R6); after that the
   * failure is recorded as an error and processing carries on without the LLM.
   */
  private fun summarise(pass: Pass, attempts: Int): Boolean = Mdc.with(Mdc.PHASE to ErrorPhase.LLM.code) {
    val (unclassified, prompt) = step(pass) { (it.email.type == null) to EventFinder.promptContext(it.email) }
    val errors = mutableListOf<String>()
    fun <T> ask(name: String, question: () -> T): T? =
      try {
        question()
      } catch (e: LlmException) {
        if (e.transient && attempts < MAX_ATTEMPTS) {
          throw Deferred(e)
        }
        errors += "$name: ${e.message}"
        null
      }
    try {
      val classification = if (unclassified) ask("classify") { llm.chat(prompts.render("classify", prompt)) } else null
      val summary = ask("summarise") { llm.chatJson(prompts.render("summarise", prompt)) }
      step(pass) { context ->
        errors.forEach { context.addError(ErrorPhase.LLM, it) }
        classification?.let { applyClassification(context, it) }
        summary?.let { applySummary(context, it) }
        context.email.progress = ProcessingProgress.SUMMARISED
      }
      true
    } catch (e: Deferred) {
      LOG.warnf("The LLM isn't available (%s), so the email will be finished on the next run", e.cause?.message)
      false
    }
  }

  /** Every matching action rule, in priority order. */
  private fun matchActionRules(pass: Pass) = Mdc.with(Mdc.PHASE to ErrorPhase.ACTION.code) {
    step(pass) { context ->
      val email = context.email
      var position = email.ruleMatches.size
      for (rule in rules.listEnabled(RuleType.ACTION)) {
        if (engine.matches(rule, context.facts())) {
          Mdc.with(Mdc.RULE to rule.name) { LOG.info("Action rule matched") }
          recordMatch(email, rule, position++)
        }
      }
      email.progress = ProcessingProgress.MATCHED
    }
  }

  /**
   * Runs the matched rules' actions that pass [only] and have no result yet, one at a time, each in
   * a transaction of its own (R6). An action that can't be repeated is recorded as started, and
   * that saved, before it runs. Returns false if the application is stopping.
   */
  private fun runActions(pass: Pass, only: (QueuedAction) -> Boolean): Boolean {
    while (true) {
      if (stopping.get()) {
        return false
      }
      var started: Long? = null
      val ran = step(pass) { context ->
        val next = executor.next(context, executor.ordered(queueOf(context.email).filter(only)), pass.started)
            ?: return@step false
        Mdc.with(Mdc.PHASE to next.phase.code) {
          val id = next.action.id!!
          if (next.handler?.repeatable == false && id !in pass.started) {
            executor.markStarted(context, next)
            started = id
          } else {
            executor.run(context, next)
          }
        }
        true
      }
      started?.let { pass.started += it }
      if (!ran) {
        return true
      }
    }
  }

  /** The actions of the rules that matched the email, in the order the rules matched. */
  private fun queueOf(email: EmailEntity): List<QueuedAction> {
    var order = 0
    return email.ruleMatches.sortedWith(compareBy({ it.position }, { it.id })).flatMap { match ->
      val rule = match.rule ?: return@flatMap emptyList()
      if (match.ruleType == RuleType.CLASSIFICATION) {
        executor.actionsOf(rule, 0, ErrorPhase.CLASSIFICATION)
      } else {
        executor.actionsOf(rule, ++order, ErrorPhase.ACTION)
      }
    }
  }

  /** The LLM's choice of type (the classify prompt). */
  private fun applyClassification(context: ProcessingContext, answer: String) {
    val name = answer.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim().trim('"', '\'', '.', '*').trim()
    if (name.equals(NO_CLASSIFICATION, ignoreCase = true) || name.isEmpty()) {
      return
    }
    val type = types.listActive().firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (type == null) {
      context.addError(ErrorPhase.LLM, "The LLM chose '$name', which isn't a type")
    } else {
      LOG.infof("The LLM classified it as %s", type.name)
      context.email.assignType(type)
    }
  }

  /** The LLM's summary (the summarise prompt). */
  private fun applySummary(context: ProcessingContext, answer: JsonNode) {
    val email = context.email
    answer.path("summary").asText("").trim().ifEmpty { null }?.let { email.summary = it }
    if (email.link == null) {
      answer.path("link").asText("").trim().takeIf { it.startsWith("http", ignoreCase = true) }?.let { email.link = it.take(2000) }
    }
    if (answer.path("response_required").asBoolean(false)) {
      email.responseRequired = true
    }
    addActionItems(context, answer)
    events.addEvents(context, answer.path("events"))
    answer.path("errors").forEach { context.addError(ErrorPhase.LLM, "summarise: ${it.asText()}") }
  }

  /**
   * The action items in the LLM's answer: `action_items`, each with an `action`, an optional `due`
   * date and `notes` (R5). An edited prompt may still give the single `action_required` and `action`
   * instead, which becomes one item.
   */
  private fun addActionItems(context: ProcessingContext, answer: JsonNode) {
    val email = context.email
    val items = answer.path("action_items")
    if (items.isArray) {
      for (item in items) {
        val description = (if (item.isTextual) item.asText() else item.path("action").asText("")).trim()
        if (description.isEmpty()) {
          continue
        }
        val due = item.path("due").asText("").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { text ->
          try {
            LocalDate.parse(text.take(10))
          } catch (e: DateTimeParseException) {
            context.addError(ErrorPhase.LLM, "summarise: '$text' isn't a date (yyyy-mm-dd) for '$description'")
            null
          }
        }
        email.addActionItem(description, due, item.path("notes").asText("").trim().takeIf { it != "null" })
      }
    } else if (answer.path("action_required").asBoolean(false)) {
      email.addActionItem(answer.path("action").asText("").trim().ifEmpty { DEFAULT_ACTION })
    }
  }

  private fun recordMatch(email: EmailEntity, rule: RuleEntity, position: Int) {
    email.ruleMatches.add(EmailRuleMatchEntity().also {
      it.email = email
      it.rule = rule
      it.ruleType = rule.ruleType
      it.position = position
      it.matchedAt = Times.now()
    })
  }

  private fun finish(email: EmailEntity, status: EmailStatus) {
    if (email.type?.isBill == true) {
      email.dropActionItems()
    }
    email.status = status
    email.progress = null
    email.processedAt = Times.now()
    LOG.infof("Email %s: type %s, %d error(s)", status.code, email.type?.name ?: "none", email.errors.size)
  }

  private fun create(message: ParsedMessage): EmailEntity {
    val email = EmailEntity()
    email.messageId = message.messageId
    email.imapUid = message.uid
    email.uidValidity = message.uidValidity
    email.folder = message.folder
    email.subject = message.subject
    email.sender = message.sender?.take(320)
    email.senderName = message.senderName?.take(255)
    email.message = message.text
    email.htmlMessage = message.html
    email.isRead = message.isRead
    email.receivedAt = message.receivedAt
    email.status = EmailStatus.PENDING
    email.progress = ProcessingProgress.RECORDED
    val extensions = settings.getList(SettingKeys.ATTACHMENTS_EXTENSIONS).map { it.lowercase(Locale.ROOT).trimStart('.') }
    for (part in message.attachments) {
      val extension = part.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
      if (extensions.isEmpty() || extension in extensions) {
        email.attachments.add(EmailAttachmentEntity().also {
          it.email = email
          it.filename = part.filename.take(500)
          it.contentType = part.contentType
          it.sizeBytes = part.size.takeIf { size -> size >= 0 }
          it.partPath = part.partPath
        })
      }
    }
    return email
  }

  private fun reset(email: EmailEntity) {
    email.ruleMatches.clear()
    email.actionResults.clear()
    email.errors.clear()
    email.events.removeIf { !it.isAdded }
    email.assignType(null)
    email.summary = null
    email.link = null
    email.linkContents = null
    email.linkSummary = null
    email.billSender = null
    email.billAmount = null
    email.billDueDate = null
    email.billPayLink = null
    email.billDownloadUrl = null
    email.responseRequired = false
    email.dropActionItems()
    email.status = EmailStatus.PENDING
    email.progress = ProcessingProgress.RECORDED
    email.attempts = 0
  }

  /**
   * Lets processing reach the end of its current step before the application stops, waiting up to
   * [SHUTDOWN_WAIT]; no further step starts. Whatever is left is carried on with by the next run
   * after a restart (R6).
   */
  fun onStop(@Observes event: ShutdownEvent) {
    stopping.set(true)
    val deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos()
    while ((running.get() || active.isNotEmpty()) && System.nanoTime() < deadline) {
      Thread.sleep(200)
    }
    if (running.get() || active.isNotEmpty()) {
      LOG.warn("Stopping in the middle of processing; the next run carries on from the last step saved")
    }
  }

  /** Marks runs that were interrupted by a restart as failed. */
  fun onStart(@Observes event: StartupEvent) {
    QuarkusTransaction.requiringNew().run {
      runs.list("status", RunStatus.RUNNING).forEach {
        it.status = RunStatus.FAILED
        it.error = "Interrupted by a restart"
        it.finishedAt = Times.now()
      }
    }
  }

  companion object {
    private val LOG = Logger.getLogger(ProcessingService::class.java)
    private const val NO_CLASSIFICATION = "NO CLASSIFICATION"
    private const val TRANSACTION_TIMEOUT = 1800

    /** Passes over an email before it's given up on (R6). */
    const val MAX_ATTEMPTS = 5

    private val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(20)

    /** An action item's description when the LLM said action is needed but not what. */
    const val DEFAULT_ACTION = "The email asks you to do something."

    /** Classification rule actions that need the LLM's summary, so they run after it (CLARIFICATIONS.md Q12). */
    private val AFTER_SUMMARY = setOf(ActionTypes.FETCH_LINK_CONTENTS, ActionTypes.CHECK_FOR_EVENTS)
  }
}

/** Runs processing every processing.frequency (CLARIFICATIONS.md D17); changes apply within a minute. */
@ApplicationScoped
class ProcessingScheduler(
    private val processing: ProcessingService,
    private val runs: ProcessingRunRepository,
    private val settings: SettingsService,
    private val config: sh.hutch.postmaster.config.PostmasterConfig
) {
  @io.quarkus.scheduler.Scheduled(
      every = "60s", delayed = "30s",
      concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
  )
  fun tick() {
    if (config.schedulerEnabled()) {
      runIfDue()
    }
  }

  /**
   * Starts a scheduled run if processing.frequency has passed since the latest run started (or
   * there's been no run), unless a run is in progress. Returns the run, or null if none was due.
   */
  fun runIfDue(): ProcessingRunEntity? {
    if (processing.isRunning) {
      return null
    }
    val due = QuarkusTransaction.requiringNew().call {
      val latest = runs.findLatest()?.startedAt
      val frequency = runCatching { settings.getDuration(SettingKeys.PROCESSING_FREQUENCY) }.getOrDefault(Duration.ofHours(1))
      latest == null || !latest.plus(frequency).isAfter(Times.now())
    }
    return if (due) processing.runNow(RunTrigger.SCHEDULE) else null
  }
}
