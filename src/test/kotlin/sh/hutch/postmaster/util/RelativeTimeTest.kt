package sh.hutch.postmaster.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant

// REQ User interface style: "Timestamps for when something was received are
// to be rendered as relative values, e.g. 14m ago, 2d ago. Days is the
// largest level of granularity." Rules at the boundaries are in
// docs/dev/contracts.md (under a minute is "just now"; always rounded down).
class RelativeTimeTest {
  @ParameterizedTest(name = "{0} ago -> {1}")
  @CsvSource(
      "PT0S, just now",
      "PT1S, just now",
      "PT59S, just now",
      "PT59.999S, just now",
      "PT60S, 1m ago",
      "PT14M, 14m ago",
      "PT59M59S, 59m ago",
      "PT60M, 1h ago",
      "PT5H30M, 5h ago",
      "PT23H59M59S, 23h ago",
      "PT24H, 1d ago",
      "PT47H59M, 1d ago",
      "P2D, 2d ago",
      "P45D, 45d ago",
      "P400D, 400d ago",
      "P3650D, 3650d ago"
  )
  fun `elapsed times are shown in the largest whole unit up to days`(elapsed: String, expected: String) {
    val now = Instant.parse("2026-10-23T06:40:00Z")

    assertEquals(expected, RelativeTime.format(now.minus(Duration.parse(elapsed)), now))
  }

  @ParameterizedTest(name = "{0} in the future -> just now")
  @CsvSource("PT1S", "PT5M", "P1D")
  fun `a time in the future is just now`(ahead: String) {
    val now = Instant.parse("2026-10-23T06:40:00Z")

    assertEquals("just now", RelativeTime.format(now.plus(Duration.parse(ahead)), now))
  }
}
