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
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.HealthReport
import sh.hutch.postmaster.models.HealthStatus
import sh.hutch.postmaster.models.ProcessingInfo
import sh.hutch.postmaster.models.RunInfo
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.util.Times
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Checks the application and what it depends on, for the status endpoint (CLARIFICATIONS.md R7).
 * The checks run in parallel, each giving up after [CHECK_TIMEOUT], so the report takes a few
 * seconds at most. The database and Dovecot are critical: without them the application can't do
 * its job, so either being down makes the status DOWN. Without the LLM or SMTP it carries on with
 * less (email waits for the LLM; forwarding fails), so the status is DEGRADED.
 */
@ApplicationScoped
class HealthService(
    private val dataSource: AgroalDataSource,
    private val settings: SettingsService,
    private val llm: LlmClient,
    private val mailbox: MailboxClient,
    private val processing: ProcessingService,
    private val dashboard: DashboardService,
    private val runs: ProcessingRunRepository,
    private val emails: EmailRepository,
    private val build: BuildInfo,
    private val config: PostmasterConfig,
    private val workers: ManagedExecutor,
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "postmaster") private val name: String,
    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "") private val jdbcUrl: String
) {

  fun report(): HealthReport {
    val checks = linkedMapOf(
        "database" to check(critical = true, type = "datastore", target = { databaseTarget() }) { database() },
        "dovecot" to check(critical = true, type = "mail", target = { hostAndPort(SettingKeys.IMAP_HOST, SettingKeys.IMAP_PORT) }) {
          mailbox.checkImap(CHECK_TIMEOUT)
          null
        },
        "llm" to check(critical = false, type = "http", target = { settings.getString(SettingKeys.LLM_URL) }) {
          llm.checkModels(CHECK_TIMEOUT).takeIf { it.isNotEmpty() }?.let { "Models: ${it.joinToString(", ")}" }
        },
        "smtp" to check(critical = false, type = "mail", target = { hostAndPort(SettingKeys.SMTP_HOST, SettingKeys.SMTP_PORT) }) {
          mailbox.checkSmtp(CHECK_TIMEOUT)
          null
        }
    ).mapValues { (_, check) -> check.await() }
    val status = when {
      checks.values.any { it.critical && it.status == CheckStatus.DOWN } -> HealthStatus.DOWN
      checks.values.any { it.status == CheckStatus.DOWN } -> HealthStatus.DEGRADED
      else -> HealthStatus.OK
    }
    val now = Times.now()
    val up = Duration.between(build.startedAt, now).truncatedTo(ChronoUnit.SECONDS)
    return HealthReport(
        status = status,
        time = now,
        application = ApplicationInfo(name, build.version, build.builtAt, build.startedAt, up.toString(), up.seconds),
        processing = processingInfo(),
        checks = checks
    )
  }

  /** A check running on a worker thread, and when it started. */
  private inner class RunningCheck(
      val critical: Boolean,
      val type: String,
      val target: () -> String?,
      val future: CompletableFuture<String?>,
      val started: Long = System.nanoTime()
  ) {
    fun await(): DependencyCheck {
      val target = runCatching { target() }.getOrNull()
      return try {
        val output = future.get(CHECK_TIMEOUT.toMillis() + GRACE_MILLIS, TimeUnit.MILLISECONDS)
        DependencyCheck(CheckStatus.UP, critical, type, target, elapsed(), output)
      } catch (e: TimeoutException) {
        DependencyCheck(CheckStatus.DOWN, critical, type, target, elapsed(), "No answer within ${CHECK_TIMEOUT.seconds}s")
      } catch (e: Exception) {
        val cause = (e.cause ?: e)
        DependencyCheck(CheckStatus.DOWN, critical, type, target, elapsed(), cause.message ?: cause.javaClass.simpleName)
      }
    }

    private fun elapsed() = (System.nanoTime() - started) / 1_000_000
  }

  private fun check(critical: Boolean, type: String, target: () -> String?, block: () -> String?): RunningCheck =
    RunningCheck(critical, type, target, workers.supplyAsync { block() })

  private fun database(): String? {
    dataSource.connection.use { connection ->
      if (!connection.isValid(CHECK_TIMEOUT.seconds.toInt())) {
        throw IllegalStateException("The database connection isn't valid")
      }
    }
    return null
  }

  /** The JDBC URL without its options, which can hold credentials. */
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

    /** How long each dependency gets to answer. */
    val CHECK_TIMEOUT: Duration = Duration.ofSeconds(5)
    private const val GRACE_MILLIS = 1000L
  }
}
