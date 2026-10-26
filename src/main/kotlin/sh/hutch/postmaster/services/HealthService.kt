package sh.hutch.postmaster.services

import io.agroal.api.AgroalDataSource
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import sh.hutch.postmaster.config.BuildInfo
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.models.ApplicationInfo
import sh.hutch.postmaster.models.CheckStatus
import sh.hutch.postmaster.models.DependencyCheck
import sh.hutch.postmaster.models.DraftCheck
import sh.hutch.postmaster.models.DraftHealthReport
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.HealthReport
import sh.hutch.postmaster.models.HealthStatus
import sh.hutch.postmaster.models.ProcessingInfo
import sh.hutch.postmaster.models.RunInfo
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.models.TodoStatus
import sh.hutch.postmaster.repositories.EmailActionItemRepository
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.util.IsoDurations
import sh.hutch.postmaster.util.Processes
import sh.hutch.postmaster.util.Times
import java.io.IOException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Checks the application and what it depends on, for the status endpoint (CLARIFICATIONS.md R7).
 * The checks run in parallel, each giving up after [CHECK_TIMEOUT], so the report takes a few
 * seconds at most. The database and Dovecot are critical: without them the application can't do
 * its job, so either being down makes the status DOWN. Any other check that's down or warns makes
 * it DEGRADED: Postmaster carries on, but something it does fails or is falling behind.
 */
@ApplicationScoped
class HealthService(
    private val dataSource: AgroalDataSource,
    private val settings: SettingsService,
    private val llm: LlmClient,
    private val mailbox: MailboxClient,
    private val khal: KhalConfigService,
    private val processing: ProcessingService,
    private val dashboard: DashboardService,
    private val runs: ProcessingRunRepository,
    private val emails: EmailRepository,
    private val items: EmailActionItemRepository,
    private val build: BuildInfo,
    private val config: PostmasterConfig,
    private val workers: ManagedExecutor,
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "postmaster") private val name: String,
    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "") private val jdbcUrl: String
) {

  fun report(): HealthReport {
    val running = linkedMapOf(
        "database" to check(critical = true, type = "datastore", target = { databaseTarget() }) { database() },
        "dovecot" to check(critical = true, type = "mail", target = { hostAndPort(SettingKeys.IMAP_HOST, SettingKeys.IMAP_PORT) }) {
          mailbox.checkImap(CHECK_TIMEOUT)
          up()
        },
        "llm" to check(critical = false, type = "http", target = { settings.getString(SettingKeys.LLM_URL) }) {
          up(llm.checkModels(CHECK_TIMEOUT).takeIf { it.isNotEmpty() }?.let { "Models: ${it.joinToString(", ")}" })
        },
        "smtp" to check(critical = false, type = "mail", target = { hostAndPort(SettingKeys.SMTP_HOST, SettingKeys.SMTP_PORT) }) {
          mailbox.checkSmtp(CHECK_TIMEOUT)
          up()
        },
        "settings" to check(critical = false, type = "component", target = { null }) { encryptedSettings() },
        "khal" to check(critical = false, type = "system", target = { config.khalConfig() }) { khal() },
        "chromium" to check(critical = false, type = "system", target = { config.chromiumCommand() }) { version(config.chromiumCommand()) },
        "curl" to check(critical = false, type = "system", target = { config.curlCommand() }) { version(config.curlCommand()) },
        "attachments" to check(critical = false, type = "system", target = { settings.getString(SettingKeys.ATTACHMENTS_PATH) }) {
          folder(SettingKeys.ATTACHMENTS_PATH)
        },
        "todo" to check(critical = false, type = "system", target = { settings.getString(SettingKeys.TODO_PATH) }) {
          folder(SettingKeys.TODO_PATH)
        },
        "todo_list" to check(critical = false, type = "system", target = { todoIndex()?.toString() }) { todoList() },
        "disk" to check(critical = false, type = "system", target = { null }) { disk() }
    )
    // Read here while the other checks run; the processing check is worked out from it
    val processingStarted = System.nanoTime()
    val processing = processingInfo()
    val processingCheck = processingCheck(processing, (System.nanoTime() - processingStarted) / 1_000_000)
    val checks = linkedMapOf<String, DependencyCheck>()
    for ((name, check) in running) {
      checks[name] = check.await()
      if (name == "settings") {
        checks["processing"] = processingCheck
      }
    }
    val status = when {
      checks.values.any { it.critical && it.status == CheckStatus.DOWN } -> HealthStatus.DOWN
      checks.values.any { it.status != CheckStatus.UP } -> HealthStatus.DEGRADED
      else -> HealthStatus.OK
    }
    val now = Times.now()
    val up = Duration.between(build.startedAt, now).truncatedTo(ChronoUnit.SECONDS)
    return HealthReport(
        status = status,
        time = now,
        application = ApplicationInfo(name, build.version, build.commit, build.builtAt, build.startedAt, up.toString(), up.seconds),
        processing = processing,
        checks = checks
    )
  }

  /** [report] in the IETF draft's format, for `application/health+json`. */
  fun draft(report: HealthReport): DraftHealthReport {
    val problems = report.checks.filterValues { it.status != CheckStatus.UP }
        .map { (name, check) -> "$name: ${check.output ?: check.status.name.lowercase()}" }
    val checks = linkedMapOf(
        "uptime" to listOf(DraftCheck(null, "system", report.application.uptimeSeconds, "s", CheckStatus.UP.draft, report.time, null))
    )
    for ((name, check) in report.checks) {
      checks["$name:responseTime"] = listOf(
          DraftCheck(check.target, check.componentType, check.responseTimeMs, "ms", check.status.draft, report.time, check.output)
      )
    }
    return DraftHealthReport(
        status = report.status.draft,
        version = report.application.version,
        releaseId = report.application.commit ?: report.application.version,
        serviceId = report.application.name,
        description = DESCRIPTION,
        output = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        checks = checks
    )
  }

  /** What a check found: [status] with some detail, or why it warns. A check that's down throws instead. */
  private class Outcome(val status: CheckStatus, val output: String?)

  private fun up(output: String? = null) = Outcome(CheckStatus.UP, output)

  private fun warn(output: String) = Outcome(CheckStatus.WARN, output)

  /** A check running on a worker thread, and when it started. */
  private inner class RunningCheck(
      val critical: Boolean,
      val type: String,
      val target: () -> String?,
      val future: CompletableFuture<Outcome>,
      val started: Long = System.nanoTime()
  ) {
    fun await(): DependencyCheck {
      val target = runCatching { target() }.getOrNull()
      return try {
        val outcome = future.get(CHECK_TIMEOUT.toMillis() + GRACE_MILLIS, TimeUnit.MILLISECONDS)
        DependencyCheck(outcome.status, critical, type, target, elapsed(), outcome.output)
      } catch (e: TimeoutException) {
        DependencyCheck(CheckStatus.DOWN, critical, type, target, elapsed(), "No answer within ${CHECK_TIMEOUT.seconds}s")
      } catch (e: Exception) {
        val cause = (e.cause ?: e)
        DependencyCheck(CheckStatus.DOWN, critical, type, target, elapsed(), cause.message ?: cause.javaClass.simpleName)
      }
    }

    private fun elapsed() = (System.nanoTime() - started) / 1_000_000
  }

  private fun check(critical: Boolean, type: String, target: () -> String?, block: () -> Outcome): RunningCheck =
    RunningCheck(critical, type, target, workers.supplyAsync { block() })

  private fun database(): Outcome {
    dataSource.connection.use { connection ->
      if (!connection.isValid(CHECK_TIMEOUT.seconds.toInt())) {
        throw IllegalStateException("The database connection isn't valid")
      }
    }
    return up()
  }

  /** Whether the encryption key can still decrypt the passwords and keys stored in the settings. */
  private fun encryptedSettings(): Outcome {
    val broken = settings.undecryptable()
    if (broken.isNotEmpty()) {
      throw IllegalStateException(
          "These settings can't be decrypted with postmaster.encryption-key; enter them again: ${broken.joinToString(", ")}"
      )
    }
    return up()
  }

  /** khal runs with its configuration, which lists calendars whose folders can be written to. */
  private fun khal(): Outcome {
    val result = run(listOf(config.khalCommand(), "-c", config.khalConfig(), "printcalendars"))
    val calendars = khal.calendarPaths()
    if (calendars.isEmpty()) {
      throw IllegalStateException("The khal configuration lists no calendars with a path")
    }
    val unwritable = calendars.filter { !Files.isDirectory(it.path) || !Files.isWritable(it.path) }
    if (unwritable.isNotEmpty()) {
      throw IllegalStateException("Calendar folders that can't be written to: ${unwritable.joinToString(", ") { "${it.name} (${it.path})" }}")
    }
    val names = result.lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { calendars.map { it.name } }
    return up("Calendars: ${names.joinToString(", ")}")
  }

  /** The tool runs and says which version it is. */
  private fun version(command: String): Outcome =
    up(run(listOf(command, "--version")).lines().firstOrNull { it.isNotBlank() }?.trim())

  /** Runs [command]; its output, or an exception saying why it failed. */
  private fun run(command: List<String>): String {
    val result = try {
      Processes.run(command, CHECK_TIMEOUT)
    } catch (e: IOException) {
      throw IllegalStateException("${command.first()} could not be started: ${e.message?.trim()}", e)
    }
    if (result.timedOut) {
      throw IllegalStateException("${command.first()} didn't finish within ${CHECK_TIMEOUT.seconds}s")
    }
    if (result.exitCode != 0) {
      val error = (result.stderr.ifBlank { result.stdout }).lines().lastOrNull { it.isNotBlank() }?.trim()
      throw IllegalStateException("${command.first()} failed with exit code ${result.exitCode}" + (error?.let { ": $it" } ?: ""))
    }
    return result.stdout
  }

  /** The folder in setting [key] can be written to, or can be created when it's first needed. */
  private fun folder(key: String): Outcome {
    val path = Path.of(settings.getString(key) ?: throw IllegalStateException("The folder isn't set ($key)"))
    if (Files.exists(path)) {
      if (!Files.isDirectory(path)) {
        throw IllegalStateException("$path isn't a folder")
      }
      if (!Files.isWritable(path)) {
        throw IllegalStateException("$path can't be written to")
      }
      return up()
    }
    val parent = existingAncestor(path)
    if (parent == null || !Files.isWritable(parent)) {
      throw IllegalStateException("$path doesn't exist and can't be created")
    }
    return up("Doesn't exist yet; it will be created when it's first needed")
  }

  private fun todoIndex(): Path? = settings.getString(SettingKeys.TODO_PATH)?.let { Path.of(it).resolve(TodoList.INDEX) }

  /**
   * The to-do list's index.md (CLARIFICATIONS.md R9): it can be read and written and has the line
   * items go above, and the files of the items Postmaster wrote are there.
   */
  private fun todoList(): Outcome {
    val index = todoIndex() ?: throw IllegalStateException("The to-do list folder isn't set (${SettingKeys.TODO_PATH})")
    if (!Files.exists(index)) {
      return warn("$index doesn't exist; it's created when the first item is added, but check ${SettingKeys.TODO_PATH} is the to-do list's folder")
    }
    if (!Files.isRegularFile(index) || !Files.isReadable(index)) {
      throw IllegalStateException("$index can't be read")
    }
    if (!Files.isWritable(index)) {
      throw IllegalStateException("$index can't be written to, so items can't be added or ticked off")
    }
    if (Files.readAllLines(index).none { it.trim() == TodoList.END_MARKER }) {
      throw IllegalStateException("$index has no ${TodoList.END_MARKER} line, so items can't be added")
    }
    val listed = QuarkusTransaction.requiringNew().timeout(CHECK_TIMEOUT.seconds.toInt()).call {
      items.listInTodoList().map { it.todoName!! to it.status }
    }
    val counts = TodoStatus.entries.associateWith { status -> listed.count { it.second == status } }
    val summary = "${listed.size} item${if (listed.size == 1) "" else "s"} from Postmaster: ${counts[TodoStatus.OPEN]} open, " +
        "${counts[TodoStatus.IN_PROGRESS]} in progress, ${counts[TodoStatus.DONE]} done"
    val missing = listed.map { it.first }.filter { !Files.exists(index.resolveSibling("$it.md")) }
    if (missing.isNotEmpty()) {
      val names = missing.take(MAX_LISTED).joinToString(", ") + if (missing.size > MAX_LISTED) " and ${missing.size - MAX_LISTED} more" else ""
      return warn("$summary; the files of ${missing.size} are missing: $names")
    }
    if (settings.getString(SettingKeys.TODO_POSTMASTER_URL).isNullOrBlank()) {
      // Reply tasks in particular link back to their email (R11)
      return warn("$summary; ${SettingKeys.TODO_POSTMASTER_URL} isn't set, so items don't link back to their email")
    }
    return up(summary)
  }

  /** Free space on the disks holding attachments, the to-do list and the calendars. */
  private fun disk(): Outcome {
    val minFree = settings.getInt(SettingKeys.HEALTH_MIN_FREE_MB).toLong() * MIB
    val paths = listOfNotNull(
        settings.getString(SettingKeys.ATTACHMENTS_PATH)?.let { Path.of(it) },
        settings.getString(SettingKeys.TODO_PATH)?.let { Path.of(it) }
    ) + khal.calendarPaths().map { it.path }
    // Each disk once, named by the first folder on it
    val disks = linkedMapOf<FileStore, Path>()
    for (path in paths) {
      val existing = existingAncestor(path) ?: continue
      disks.putIfAbsent(Files.getFileStore(existing), path)
    }
    if (disks.isEmpty()) {
      return up("No folders to check")
    }
    val free = disks.map { (store, path) -> path to store.usableSpace }
    val text = free.joinToString("; ") { (path, bytes) -> "$path: ${size(bytes)} free" }
    return if (free.any { it.second < minFree }) warn("Less than ${size(minFree)} free: $text") else up(text)
  }

  private fun existingAncestor(path: Path): Path? {
    var current: Path? = path.toAbsolutePath()
    while (current != null && !Files.exists(current)) {
      current = current.parent
    }
    return current
  }

  /** The processing check: whether scheduled runs are succeeding and email isn't piling up. */
  private fun processingCheck(info: ProcessingInfo?, elapsedMs: Long): DependencyCheck {
    val outcome = try {
      if (info == null) {
        throw IllegalStateException("The processing status couldn't be read")
      }
      val frequency = runCatching { settings.getDuration(SettingKeys.PROCESSING_FREQUENCY) }.getOrDefault(Duration.ofHours(1))
      val limit = settings.getInt(SettingKeys.HEALTH_PENDING_LIMIT)
      processingOutcome(info, frequency, limit, build.startedAt, Times.now())
    } catch (e: Exception) {
      return DependencyCheck(CheckStatus.DOWN, false, "component", null, elapsedMs, e.message ?: e.javaClass.simpleName)
    }
    return DependencyCheck(outcome.first, false, "component", null, elapsedMs, outcome.second)
  }

  private fun databaseTarget(): String? =
    jdbcUrl.substringBefore('?').substringBefore(';').takeIf { it.isNotBlank() }

  private fun hostAndPort(host: String, port: String): String? =
    settings.getString(host)?.takeIf { it.isNotBlank() }?.let { "$it:${settings.getString(port).orEmpty()}" }

  /** What processing is doing; null if the database can't be read. */
  private fun processingInfo(): ProcessingInfo? =
    try {
      QuarkusTransaction.requiringNew().timeout(CHECK_TIMEOUT.seconds.toInt()).call {
        val dayAgo = Times.now().minus(Duration.ofDays(1))
        ProcessingInfo(
            inProgress = processing.isRunning,
            currentRun = runs.findRunning()?.let { view(it) },
            lastRun = runs.find("status != ?1 order by startedAt desc, id desc", RunStatus.RUNNING).firstResult()?.let { view(it) },
            lastSuccessAt = runs.findLatestSucceeded()?.startedAt,
            scheduled = config.schedulerEnabled(),
            nextRunAt = dashboard.nextRunAt(),
            pendingEmails = emails.count("status = ?1 and deleted = false", EmailStatus.PENDING),
            failedEmailsLastDay = emails.count("status = ?1 and deleted = false and processedAt >= ?2", EmailStatus.FAILED, dayAgo)
        )
      }
    } catch (e: Exception) {
      LOG.warnf("The processing status couldn't be read: %s", e.message)
      null
    }

  private fun view(run: ProcessingRunEntity) =
    RunInfo(
        run.id!!, run.triggerSource.code, run.status.code, run.startedAt, run.finishedAt, run.emailsFound, run.emailsProcessed,
        run.emailsFailed, run.error
    )

  companion object {
    private val LOG = Logger.getLogger(HealthService::class.java)

    /** How long each check gets to answer. */
    val CHECK_TIMEOUT: Duration = Duration.ofSeconds(5)
    private const val GRACE_MILLIS = 1000L
    private const val MAX_LISTED = 5
    private const val MIB = 1024L * 1024
    private const val DESCRIPTION = "Postmaster: email processing, classification and actions"

    /**
     * Whether processing is keeping up: WARN when scheduled runs haven't succeeded for two
     * [frequency] periods (counting from [startedAt] when that's later, so a restart gets time to
     * run), or when more than [pendingLimit] emails are waiting.
     */
    fun processingOutcome(
        info: ProcessingInfo,
        frequency: Duration,
        pendingLimit: Int,
        startedAt: Instant,
        now: Instant
    ): Pair<CheckStatus, String?> {
      val problems = mutableListOf<String>()
      if (info.scheduled) {
        val since = listOfNotNull(info.lastSuccessAt, startedAt).max()
        if (Duration.between(since, now) > frequency.multipliedBy(2)) {
          problems += (info.lastSuccessAt?.let { "No run has succeeded since $it" } ?: "No run has succeeded since starting at $startedAt") +
              " (runs are every ${IsoDurations.format(frequency)})"
        }
      }
      if (info.pendingEmails > pendingLimit) {
        problems += "${info.pendingEmails} emails are waiting to be processed (more than $pendingLimit)"
      }
      return if (problems.isEmpty()) {
        CheckStatus.UP to (if (info.scheduled) null else "Scheduled processing is off")
      } else {
        CheckStatus.WARN to problems.joinToString("; ")
      }
    }

    /** [bytes] in MiB or GiB, e.g. `512 MiB` or `120.4 GiB`. */
    fun size(bytes: Long): String =
      if (bytes < 1024 * MIB) "${bytes / MIB} MiB" else String.format(Locale.ENGLISH, "%.1f GiB", bytes.toDouble() / (1024 * MIB))
  }
}
