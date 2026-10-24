package sh.hutch.postmaster.processing

import io.quarkus.scheduler.Scheduled
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.services.ProcessingScheduler
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Scheduled processing (REQ Fetching emails: "every hour by default, but it
 * can be configured"; PLAN 5.6; CLAR D17): a run starts once
 * processing.frequency has passed since the latest one started, never while
 * another is running, and not at all when scheduling is off.
 */
@QuarkusTest
class SchedulerTest : PipelineTest() {
  @Inject
  lateinit var scheduler: ProcessingScheduler

  @AfterEach
  fun restoreFrequency() {
    settings.update("processing.frequency", "1h")
  }

  /** Makes every run, and so the latest, look as if it started [ago] (times are stored in UTC) */
  private fun latestRunStarted(ago: Duration) {
    if (runCount() == 0L) {
      processing.runNow(RunTrigger.MANUAL)
    }
    dataSource.update("UPDATE Processing_runs SET started_at = ?", LocalDateTime.ofInstant(Instant.now().minus(ago), ZoneOffset.UTC))
  }

  private fun runCount(): Long = (dataSource.row("SELECT COUNT(*) AS n FROM Processing_runs")["n"] as Number).toLong()

  @Test
  fun `a run starts once the frequency has passed since the latest one`() {
    settings.update("processing.frequency", "1h")
    latestRunStarted(Duration.ofMinutes(61))
    inbox(message("<sched-due@mail.example>", "Scheduled"))

    val run = scheduler.runIfDue()

    assertNotNull(run)
    assertEquals("schedule", run!!.triggerSource.code)
    assertEquals("processed", email("sched-due@mail.example")["status"])
  }

  @Test
  fun `no run starts before the frequency has passed`() {
    settings.update("processing.frequency", "1h")
    latestRunStarted(Duration.ofMinutes(59))
    val before = runCount()

    assertNull(scheduler.runIfDue())

    assertEquals(before, runCount())
  }

  @Test
  fun `the frequency comes from the settings`() {
    latestRunStarted(Duration.ofMinutes(20))

    settings.update("processing.frequency", "30m")
    assertNull(scheduler.runIfDue(), "20 minutes is within 30m")

    settings.update("processing.frequency", "PT15M")
    assertNotNull(scheduler.runIfDue(), "20 minutes is past PT15M")
  }

  @Test
  fun `no scheduled run starts while another run is going`() {
    settings.update("processing.frequency", "1h")
    latestRunStarted(Duration.ofHours(2))
    llm(SUMMARISE, """{"summary": "slow"}""", delayMs = 3000)
    inbox(message("<sched-slow@mail.example>", "Slow"))
    val manual = CompletableFuture.supplyAsync { processing.runNow(RunTrigger.MANUAL) }
    Thread.sleep(800)

    assertNull(scheduler.runIfDue())

    assertEquals("succeeded", manual.get(30, TimeUnit.SECONDS)!!.status.code)
  }

  @Test
  fun `nothing runs on the schedule when scheduling is off`() {
    // The tests run with postmaster.scheduler-enabled false
    latestRunStarted(Duration.ofHours(2))
    val before = runCount()

    scheduler.tick()

    assertEquals(before, runCount())
  }

  @Test
  fun `the scheduler checks every minute and skips a check while one is running`() {
    // CLAR D17: a changed frequency applies within a minute
    val scheduled = ProcessingScheduler::class.java.getMethod("tick").getAnnotation(Scheduled::class.java)

    assertEquals("60s", scheduled.every)
    assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution)
  }
}
