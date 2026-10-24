package sh.hutch.postmaster.models

import io.quarkus.runtime.annotations.RegisterForReflection
import java.time.Instant

/**
 * The application's overall status (CLARIFICATIONS.md R7). The status endpoint answers HTTP 200 for
 * OK and DEGRADED, and 503 for DOWN.
 */
enum class HealthStatus {
  /** Everything it depends on is available. */
  OK,

  /** It works, but something it can do without is unavailable: the LLM or SMTP. */
  DEGRADED,

  /** It's running but can't do its job: the database or Dovecot is unavailable. */
  DOWN
}

/** Whether one dependency can be used. */
enum class CheckStatus { UP, DOWN }

/**
 * What the status endpoint returns (R7). The layout follows the IETF draft "Health Check Response
 * Format for HTTP APIs": an overall `status`, the release, and `checks` keyed by dependency. Every
 * field is always present, null when there's no value, so clients can rely on the shape.
 */
@RegisterForReflection
data class HealthReport(
    val status: HealthStatus,
    /** When the checks ran. */
    val time: Instant,
    val application: ApplicationInfo,
    /** Null if the database can't be read. */
    val processing: ProcessingInfo?,
    /** One entry per dependency: database, llm, dovecot and smtp. */
    val checks: Map<String, DependencyCheck>
)

@RegisterForReflection
data class ApplicationInfo(
    val name: String,
    /** The build number with a `v`, e.g. `v12`. */
    val version: String,
    /** When the build was made; null in dev mode and tests. */
    val builtAt: Instant?,
    val startedAt: Instant,
    /** ISO 8601, e.g. `PT3H12M5S`. */
    val uptime: String,
    val uptimeSeconds: Long
)

@RegisterForReflection
data class ProcessingInfo(
    /** Whether a processing run is going now. */
    val inProgress: Boolean,
    /** The run going now. */
    val currentRun: RunInfo?,
    /** The latest run that has finished. */
    val lastRun: RunInfo?,
    /** When the latest successful run started. */
    val lastSuccessAt: Instant?,
    /** Whether email is processed on a schedule. */
    val scheduled: Boolean,
    /** When the next scheduled run is due; null if scheduling is off. */
    val nextRunAt: Instant?,
    /** Emails whose processing hasn't finished, e.g. waiting for the LLM (R6). */
    val pendingEmails: Long,
    /** Emails whose processing failed in the last 24 hours. */
    val failedEmailsLastDay: Long
)

@RegisterForReflection
data class RunInfo(
    val id: Long,
    val trigger: String,
    val status: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val emailsFound: Int,
    val emailsProcessed: Int,
    val emailsFailed: Int,
    val error: String?
)

/** One dependency's check, in the IETF draft's terms where it has them. */
@RegisterForReflection
data class DependencyCheck(
    val status: CheckStatus,
    /** Whether the application can't do its job without it: DOWN then makes the overall status DOWN. */
    val critical: Boolean,
    /** `datastore`, `http` or `mail`. */
    val componentType: String,
    /** What was checked, e.g. `mail-dovecot:143`, without credentials. */
    val target: String?,
    /** How long the check took. */
    val responseTimeMs: Long,
    /** Why it's down, or other detail (e.g. the LLM's models). */
    val output: String?
)
