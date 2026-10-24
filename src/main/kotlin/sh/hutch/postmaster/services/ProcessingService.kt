package sh.hutch.postmaster.services

import com.fasterxml.jackson.databind.JsonNode
import io.quarkus.narayana.jta.QuarkusTransaction
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
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.MailboxMessage
import sh.hutch.postmaster.models.ParsedMessage
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

  /** Emails being reprocessed now, so each is reprocessed by one request at a time. */
  private val reprocessing = ConcurrentHashMap.newKeySet<Long>()

  /** Whether a run is in progress. */
  val isRunning: Boolean
    get() = running.get()

  /** Runs processing now and returns the run, or null if a run is already in progress. */
  fun runNow(trigger: RunTrigger): ProcessingRunEntity? {
    if (!running.compareAndSet(false, true)) {
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
    if (!running.compareAndSet(false, true)) {
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
      val since = Times.now().minus(settings.getDuration(SettingKeys.PROCESSING_LOOKBACK))
      val listed = mailbox.listInbox(since)
      val fresh = QuarkusTransaction.requiringNew().call { listed.filter { !emails.existsByMessageId(it.messageId) } }
      found = fresh.size
      LOG.infof("%d new of %d messages in the inbox", found, listed.size)
      for (message in fresh) {
        try {
          process(message)
          processed++
        } catch (e: Exception) {
          failed++
          LOG.warnf(e, "Processing message %s failed", message.messageId)
        }
      }
    } catch (e: Exception) {
      error = e.message ?: e.javaClass.simpleName
      LOG.warn("Processing run failed", e)
    }
    LOG.infof("Processing run finished: %d found, %d processed, %d failed%s", found, processed, failed, error?.let { "; $it" } ?: "")
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
   * Fetches and processes one listed INBOX message. If it can't be fetched, nothing is recorded and
   * a later run tries again.
   */
  fun process(listed: MailboxMessage): EmailEntity = Mdc.with(Mdc.MESSAGE_ID to listed.messageId) {
    val inbox = settings.getString(SettingKeys.IMAP_INBOX_FOLDER) ?: "INBOX"
    val message = mailbox.fetch(inbox, listed.uid)
    QuarkusTransaction.requiringNew().timeout(TRANSACTION_TIMEOUT).call {
      val email = create(message)
      emails.persist(email)
      Mdc.with(Mdc.EMAIL to email.id) {
        LOG.infof("Processing '%s' from %s", email.subject, email.sender)
        pipeline(ProcessingContext(email, message))
      }
      email
    }
  }

  /**
   * Processes an email again, from its message in the mailbox.
   *
   * @throws IllegalStateException if the email is already being reprocessed
   */
  fun reprocess(emailId: Long): EmailEntity {
    if (!reprocessing.add(emailId)) {
      throw IllegalStateException("The email is already being processed again")
    }
    try {
      return reprocessOnce(emailId)
    } finally {
      reprocessing.remove(emailId)
    }
  }

  private fun reprocessOnce(emailId: Long): EmailEntity {
    val (folder, known, messageId) = QuarkusTransaction.requiringNew().call {
      val email = emails.findActive(emailId) ?: throw jakarta.ws.rs.NotFoundException("Email $emailId not found")
      Triple(email.folder ?: settings.getString(SettingKeys.IMAP_INBOX_FOLDER) ?: "INBOX", email.imapUid?.let { it to email.uidValidity }, email.messageId)
    }
    val found = mailbox.locate(folder, messageId, known?.first, known?.second) ?: throw MailboxException("The message can't be found in $folder")
    val message = mailbox.fetch(folder, found.uid)
    return QuarkusTransaction.requiringNew().timeout(TRANSACTION_TIMEOUT).call {
      val email = emails.findActive(emailId)!!
      reset(email)
      email.imapUid = found.uid
      email.uidValidity = found.uidValidity
      email.folder = folder
      Mdc.with(Mdc.EMAIL to email.id) {
        LOG.infof("Reprocessing '%s' from %s", email.subject, email.sender)
        pipeline(ProcessingContext(email, message))
      }
      email
    }
  }

  // ---------------------------------------------------------------------------------------------

  private fun pipeline(context: ProcessingContext) {
    val email = context.email
    try {
      // Classification: the first matching rule only
      val deferred = mutableListOf<QueuedAction>()
      val queue = mutableListOf<QueuedAction>()
      var position = 0
      Mdc.with(Mdc.PHASE to ErrorPhase.CLASSIFICATION.code) {
        val classifier = rules.listEnabled(RuleType.CLASSIFICATION).firstOrNull { engine.matches(it, context.facts()) }
        if (classifier == null) {
          LOG.info("No classification rule matched")
        } else {
          Mdc.with(Mdc.RULE to classifier.name) { LOG.info("Classification rule matched") }
          recordMatch(email, classifier, position++)
          for (item in executor.actionsOf(classifier, 0, ErrorPhase.CLASSIFICATION)) {
            when {
              item.handler?.stage == ActionStage.RECORD -> executor.run(context, item)
              item.action.actionType in AFTER_SUMMARY -> deferred += item
              else -> queue += item
            }
          }
        }
      }
      if (context.stopped) {
        queue.forEach { executor.run(context, it) }
        deferred.forEach { executor.run(context, it) }
        finish(email, EmailStatus.SKIPPED)
        return
      }

      // LLM: classify if still unclassified, then summarise (CLARIFICATIONS.md Q15)
      Mdc.with(Mdc.PHASE to ErrorPhase.LLM.code) {
        if (email.type == null) {
          classify(context)
        }
        summarise(context)
        deferred.forEach { executor.run(context, it) }
      }

      // Action rules: every one that matches
      Mdc.with(Mdc.PHASE to ErrorPhase.ACTION.code) {
        rules.listEnabled(RuleType.ACTION).forEachIndexed { index, rule ->
          if (engine.matches(rule, context.facts())) {
            Mdc.with(Mdc.RULE to rule.name) { LOG.info("Action rule matched") }
            recordMatch(email, rule, position++)
            queue += executor.actionsOf(rule, index + 1, ErrorPhase.ACTION)
          }
        }
        executor.runQueue(context, queue)
      }
      finish(email, if (context.stopped) EmailStatus.SKIPPED else EmailStatus.PROCESSED)
    } catch (e: Exception) {
      LOG.warnf(e, "Processing email %s failed", email.messageId)
      context.addError(ErrorPhase.ACTION, "Processing failed: ${e.message ?: e.javaClass.simpleName}")
      finish(email, EmailStatus.FAILED)
    }
  }

  private fun classify(context: ProcessingContext) {
    try {
      val answer = llm.chat(prompts.render("classify", EventFinder.promptContext(context.email)))
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
    } catch (e: LlmException) {
      context.addError(ErrorPhase.LLM, "classify: ${e.message}")
    }
  }

  private fun summarise(context: ProcessingContext) {
    val email = context.email
    try {
      val answer = llm.chatJson(prompts.render("summarise", EventFinder.promptContext(email)))
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
    } catch (e: LlmException) {
      context.addError(ErrorPhase.LLM, "summarise: ${e.message}")
    }
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
