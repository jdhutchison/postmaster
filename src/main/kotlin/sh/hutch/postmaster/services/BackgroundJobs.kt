package sh.hutch.postmaster.services

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.util.Times
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the jobs besides processing, each every so often (CLARIFICATIONS.md R9): scanning the
 * mailbox's folders (imap.folder_scan_frequency), looking for tracked emails (tracking.frequency)
 * and reading the to-do list (todo.sync_frequency). Each runs soon after startup and then at its
 * interval; a changed interval applies within a minute. A job that fails is tried again at its next
 * interval.
 */
@ApplicationScoped
class BackgroundJobs(
    private val folders: FolderService,
    private val tracking: TrackingService,
    private val todos: TodoListService,
    private val settings: SettingsService,
    private val config: PostmasterConfig
) {
  /** When each job last started. */
  private val lastRun = ConcurrentHashMap<String, Instant>()

  @Scheduled(every = "60s", delayed = "40s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  fun scanFolders() {
    runIfDue(FOLDERS, SettingKeys.IMAP_FOLDER_SCAN_FREQUENCY, Duration.ofHours(12)) { folders.scan() }
  }

  @Scheduled(every = "60s", delayed = "50s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  fun checkTracking() {
    runIfDue(TRACKING, SettingKeys.TRACKING_FREQUENCY, Duration.ofHours(1)) { tracking.check() }
  }

  @Scheduled(every = "60s", delayed = "45s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  fun syncTodoList() {
    runIfDue(TODO, SettingKeys.TODO_SYNC_FREQUENCY, Duration.ofHours(1)) {
      val changed = todos.sync()
      if (changed > 0) {
        LOG.infof("%d action item(s) changed status in the to-do list", changed)
      }
    }
  }

  /** Runs [job] if scheduling is on and [key]'s frequency has passed since it last started; whether it ran. */
  fun runIfDue(job: String, key: String, fallback: Duration, block: () -> Unit): Boolean {
    if (!config.schedulerEnabled()) {
      return false
    }
    val frequency = runCatching { settings.getDuration(key) }.getOrDefault(fallback)
    val now = Times.now()
    val last = lastRun[job]
    if (last != null && last.plus(frequency).isAfter(now)) {
      return false
    }
    lastRun[job] = now
    try {
      block()
    } catch (e: Exception) {
      LOG.warnf(e, "The %s job failed: %s", job, e.message)
    }
    return true
  }

  companion object {
    private val LOG = Logger.getLogger(BackgroundJobs::class.java)
    const val FOLDERS = "folder scan"
    const val TRACKING = "tracking check"
    const val TODO = "to-do list check"
  }
}
