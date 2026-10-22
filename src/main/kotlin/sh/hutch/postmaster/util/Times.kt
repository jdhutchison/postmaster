package sh.hutch.postmaster.util

import java.time.Instant
import java.time.temporal.ChronoUnit

object Times {
  /** The current instant, truncated to the microsecond precision of the DATETIME(6) columns. */
  fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}
