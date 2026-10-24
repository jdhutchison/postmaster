package sh.hutch.postmaster.services

import io.quarkus.panache.common.Page
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.models.Dashboard
import sh.hutch.postmaster.models.EmailCounts
import sh.hutch.postmaster.models.RunView
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.util.IsoDurations
import sh.hutch.postmaster.util.Times
import java.time.Duration
import java.time.Instant

/** What the dashboard shows: processing runs and email counts (PLAN.md 6.3). */
@ApplicationScoped
class DashboardService(
    private val runs: ProcessingRunRepository,
    private val emails: EmailRepository,
    private val processing: ProcessingService,
    private val settings: SettingsService,
    private val config: PostmasterConfig
) {

  @Transactional
  fun dashboard(): Dashboard {
    val frequency = runCatching { settings.getDuration(SettingKeys.PROCESSING_FREQUENCY) }.getOrDefault(Duration.ofHours(1))
    val latest = runs.findLatest()
    val enabled = config.schedulerEnabled()
    return Dashboard(
        running = processing.isRunning,
        latest = latest?.let { view(it) },
        nextRunAt = if (enabled) nextRun(latest?.startedAt, frequency) else null,
        frequency = IsoDurations.format(frequency),
        schedulerEnabled = enabled,
        counts = counts(),
        recentRuns = runs.find("order by startedAt desc, id desc").page(Page.ofSize(RECENT_RUNS)).list().map { view(it) }
    )
  }

  /** The latest runs, newest first. */
  @Transactional
  fun recentRuns(limit: Int): List<RunView> =
    runs.find("order by startedAt desc, id desc").page(Page.ofSize(limit)).list().map { view(it) }

  @Transactional
  fun counts(): EmailCounts {
    fun count(where: String, vararg params: Any) = emails.count("deleted = false$where", *params)
    return EmailCounts(
        total = count(""),
        lastDay = count(" and receivedAt >= ?1", Times.now().minus(Duration.ofDays(1))),
        withErrors = emails.count("from EmailEntity e where e.deleted = false and exists (select 1 from EmailErrorEntity x where x.email = e)"),
        responseRequired = count(" and responseRequired = true"),
        actionRequired = count(" and actionRequired = true"),
        unclassified = count(" and type is null")
    )
  }

  /**
   * When the scheduler will next start a run: [frequency] after the latest run started, or now if
   * that has passed. It checks once a minute, so the run starts within a minute of this.
   */
  private fun nextRun(latestStart: Instant?, frequency: Duration): Instant {
    val now = Times.now()
    val due = latestStart?.plus(frequency) ?: now
    return if (due.isBefore(now)) now else due
  }

  companion object {
    const val RECENT_RUNS = 10

    fun view(run: ProcessingRunEntity) =
      RunView(
          run.id!!, run.triggerSource, run.status, run.startedAt, run.finishedAt, run.emailsFound, run.emailsProcessed,
          run.emailsFailed, run.error
      )
  }
}
