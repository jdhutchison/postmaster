package sh.hutch.postmaster.models

import com.fasterxml.jackson.annotation.JsonInclude
import io.quarkus.runtime.annotations.RegisterForReflection
import java.time.Instant

/**
 * The application's overall status (CLARIFICATIONS.md R7). The status endpoint answers HTTP 200 for
 * OK and DEGRADED, and 503 for DOWN. [draft] is the IETF draft's word for it.
 */
enum class HealthStatus(val draft: String) {
  /** Everything it depends on is available. */
  OK("pass"),

  /** It works, but not fully: a check that isn't critical is down or warns. */
  DEGRADED("warn"),

  /** It's running but can't do its job: the database or Dovecot is unavailable. */
  DOWN("fail")
}

/** The result of one check. [draft] is the IETF draft's word for it. */
enum class CheckStatus(val draft: String) {
  UP("pass"),

  /** Usable, but something needs attention, e.g. little disk space left. */
  WARN("warn"),

  DOWN("fail")
}

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
    /**
     * One entry per check: database, dovecot, llm, smtp, settings, processing, khal, chromium, curl,
     * attachments, todo and disk.
     */
    val checks: Map<String, DependencyCheck>
)

@RegisterForReflection
data class ApplicationInfo(
    val name: String,
    /** The build number with a `v`, e.g. `v12`. */
    val version: String,
    /** The git commit it was built from, with `-dirty` if it had uncommitted changes; null if unknown. */
    val commit: String?,
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

/** One check, in the IETF draft's terms where it has them. */
@RegisterForReflection
data class DependencyCheck(
    val status: CheckStatus,
    /**
     * Whether the application can't do its job without it: DOWN then makes the overall status DOWN.
     * Any other check that's DOWN or WARN makes it DEGRADED.
     */
    val critical: Boolean,
    /** `datastore`, `mail`, `http`, `component` (part of Postmaster) or `system` (a tool or folder). */
    val componentType: String,
    /** What was checked, e.g. `mail-dovecot:143` or `/data/todo`, without credentials. */
    val target: String?,
    /** How long the check took. */
    val responseTimeMs: Long,
    /** Why it's down or warns, or other detail (e.g. the LLM's models). */
    val output: String?
)

/**
 * The status in the IETF draft's own format (`application/health+json`), for monitoring tools
 * that understand it (R7). Fields without a value are left out, as the draft asks.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DraftHealthReport(
    /** `pass`, `warn` or `fail`. */
    val status: String,
    /** The build number with a `v`, e.g. `v12`. */
    val version: String,
    /** The git commit when it's known, otherwise the version. */
    val releaseId: String,
    val serviceId: String,
    val description: String,
    /** What's wrong, when the status isn't `pass`. */
    val output: String? = null,
    /** `<check>:responseTime` for each check, and `uptime`. */
    val checks: Map<String, List<DraftCheck>>
)

/** One measurement in [DraftHealthReport.checks]. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DraftCheck(
    /** What was checked, e.g. `mail-dovecot:143`. */
    val componentId: String? = null,
    val componentType: String,
    val observedValue: Long,
    /** `ms` or `s`. */
    val observedUnit: String,
    /** `pass`, `warn` or `fail`. */
    val status: String,
    val time: Instant,
    val output: String? = null
)
