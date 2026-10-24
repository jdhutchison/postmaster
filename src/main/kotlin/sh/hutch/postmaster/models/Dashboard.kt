package sh.hutch.postmaster.models

import io.quarkus.qute.TemplateData
import java.time.Instant

/** A processing run, for the dashboard and MCP. */
@TemplateData
data class RunView(
    val id: Long,
    val trigger: RunTrigger,
    val status: RunStatus,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val emailsFound: Int,
    val emailsProcessed: Int,
    val emailsFailed: Int,
    val error: String?
)

/** Email counts for the dashboard. Deleted emails aren't counted. */
@TemplateData
data class EmailCounts(
    val total: Long,
    val lastDay: Long,
    val withErrors: Long,
    val responseRequired: Long,
    val actionRequired: Long,
    val unclassified: Long
)

/**
 * The dashboard: the latest runs, when the next one is due, and email counts. [nextRunAt] is null
 * when the scheduler is off; [frequency] is the processing.frequency setting, e.g. `1h`.
 */
@TemplateData
data class Dashboard(
    val running: Boolean,
    val latest: RunView?,
    val nextRunAt: Instant?,
    val frequency: String,
    val schedulerEnabled: Boolean,
    val counts: EmailCounts,
    val recentRuns: List<RunView>
)
