package sh.hutch.postmaster.util

import java.time.Duration
import java.time.Instant

/**
 * Formats how long ago something happened, e.g. `14m ago` or `2d ago`. Days are the largest unit
 * (`45d ago`); under a minute, or in the future, is `just now`. Each unit is rounded down.
 * postmaster.js applies the same rules when it refreshes times in the browser.
 */
object RelativeTime {
  private val MINUTE = Duration.ofMinutes(1)

  fun format(instant: Instant, now: Instant): String {
    val elapsed = Duration.between(instant, now)
    if (elapsed < MINUTE) {
      return "just now"
    }
    val minutes = elapsed.toMinutes()
    if (minutes < 60) {
      return "${minutes}m ago"
    }
    val hours = elapsed.toHours()
    if (hours < 24) {
      return "${hours}h ago"
    }
    return "${elapsed.toDays()}d ago"
  }
}
