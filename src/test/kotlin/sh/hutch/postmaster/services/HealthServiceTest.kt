package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.CheckStatus
import sh.hutch.postmaster.models.ProcessingInfo
import java.time.Duration
import java.time.Instant

/**
 * The processing check (CLAR R7): it warns when scheduled runs stop
 * succeeding or email piles up.
 */
class HealthServiceTest {
  private val now = Instant.parse("2026-10-25T10:00:00Z")
  private val hour = Duration.ofHours(1)
  private val longAgo = Instant.parse("2026-10-20T00:00:00Z")

  private fun info(lastSuccessAt: Instant?, pending: Long = 0, scheduled: Boolean = true) =
    ProcessingInfo(false, null, null, lastSuccessAt, scheduled, null, pending, 0)

  private fun outcome(info: ProcessingInfo, startedAt: Instant = longAgo) =
    HealthService.processingOutcome(info, hour, 25, startedAt, now)

  @Test
  fun `a recent success is fine`() {
    assertEquals(CheckStatus.UP to null, outcome(info(now.minus(Duration.ofMinutes(90)))))
  }

  @Test
  fun `no success for two periods warns`() {
    val (status, output) = outcome(info(Instant.parse("2026-10-25T07:30:00Z")))

    assertEquals(CheckStatus.WARN, status)
    assertEquals("No run has succeeded since 2026-10-25T07:30:00Z (runs are every 1h)", output)
  }

  @Test
  fun `a restart gets two periods to succeed`() {
    assertEquals(CheckStatus.UP, outcome(info(longAgo), startedAt = now.minus(Duration.ofMinutes(30))).first)
    assertEquals(CheckStatus.UP, outcome(info(null), startedAt = now.minus(Duration.ofMinutes(30))).first)
    assertEquals(
        CheckStatus.WARN to "No run has succeeded since starting at 2026-10-25T07:00:00Z (runs are every 1h)",
        outcome(info(null), startedAt = Instant.parse("2026-10-25T07:00:00Z"))
    )
  }

  @Test
  fun `without scheduling only the backlog matters`() {
    assertEquals(CheckStatus.UP to "Scheduled processing is off", outcome(info(null, scheduled = false)))
  }

  @Test
  fun `more pending emails than the limit warns`() {
    assertEquals(CheckStatus.UP, outcome(info(now, pending = 25)).first)
    assertEquals(
        CheckStatus.WARN to "26 emails are waiting to be processed (more than 25)",
        outcome(info(now, pending = 26))
    )
  }

  @Test
  fun `both problems are given`() {
    val (status, output) = outcome(info(longAgo, pending = 30))

    assertEquals(CheckStatus.WARN, status)
    assertEquals(
        "No run has succeeded since 2026-10-20T00:00:00Z (runs are every 1h); 30 emails are waiting to be processed (more than 25)",
        output
    )
  }

  @Test
  fun `sizes are in MiB or GiB`() {
    assertEquals("512 MiB", HealthService.size(512L * 1024 * 1024))
    assertEquals("1.0 GiB", HealthService.size(1024L * 1024 * 1024))
    assertEquals("120.4 GiB", HealthService.size((120.4 * 1024 * 1024 * 1024).toLong()))
  }
}
