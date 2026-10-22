package sh.hutch.postmaster.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

// REQ Configuration 4: "Duration or frequency based settings must support full
// and simplified ISO 8601 duration strings, e.g. PT1H and 1H or 1h".
// REQ Calendars 7: Durations are "a comma plus space separated list of
// simplified or full ISO 8601 duration strings". Simplified M is minutes and
// years/months are rejected (docs/dev/contracts.md).
class IsoDurationsTest {
  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      "PT1H, PT1H",
      "1H, PT1H",
      "1h, PT1H",
      "pt1h, PT1H",
      "P1D, PT24H",
      "1d, PT24H",
      "1D, PT24H",
      "4h, PT4H",
      "30m, PT30M",
      "30M, PT30M",
      "1h30m, PT1H30M",
      "1d2h, PT26H",
      "P1DT2H, PT26H",
      "P1W, PT168H",
      "2w, PT336H",
      "PT90S, PT1M30S",
      "45s, PT45S",
      "PT0.5S, PT0.5S"
  )
  fun `parses full and simplified durations`(text: String, expected: String) {
    assertEquals(Duration.parse(expected), IsoDurations.parse(text))
    assertTrue(IsoDurations.isValid(text))
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = [
        "", "h", "1", "1x", "P", "PT", "T1H", "1.5h", "P1M", "P1Y", "1y", " 1h", "1h ", "1 h", "PT1H ", "one hour",
        "1h-", "h1"
      ]
  )
  fun `rejects text that is not a supported duration`(text: String) {
    assertThrows<InvalidDurationException> { IsoDurations.parse(text) }
    assertFalse(IsoDurations.isValid(text))
  }

  @Test
  fun `an invalid duration is an IllegalArgumentException`() {
    assertThrows<IllegalArgumentException> { IsoDurations.parse("soon") }
  }

  @Test
  fun `parses a comma plus space separated list`() {
    // REQ Calendars 7, Calendars schema notification_periods ("e.g. 4h, 1d")
    assertEquals(listOf(Duration.ofHours(4), Duration.ofDays(1)), IsoDurations.parseList("4h, 1d"))
    assertEquals(
        listOf(Duration.ofHours(1), Duration.ofDays(1), Duration.ofMinutes(30)),
        IsoDurations.parseList("PT1H, P1D, 30m")
    )
    assertEquals(listOf(Duration.ofDays(1)), IsoDurations.parseList("1d"))
    assertTrue(IsoDurations.isValidList("4h, 1d"))
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = [
        "4h,1d", "4h ,1d", "4h , 1d", "4h,  1d", "4h; 1d", "4h 1d", "4h, ", ", 4h", "4h, , 1d", "", " 4h", "4h, 1x"
      ]
  )
  fun `rejects any other list format`(text: String) {
    assertThrows<InvalidDurationException> { IsoDurations.parseList(text) }
    assertFalse(IsoDurations.isValidList(text))
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource("PT26H, 1d2h", "PT30M, 30m", "PT0S, 0s", "PT1H, 1h", "PT1H30M, 1h30m")
  fun `formats durations in the simplified form`(duration: String, expected: String) {
    assertEquals(expected, IsoDurations.format(Duration.parse(duration)))
  }

  @Test
  fun `formatted durations parse back to the same value`() {
    for (duration in listOf(Duration.ofMinutes(90), Duration.ofDays(9), Duration.ofSeconds(3661))) {
      assertEquals(duration, IsoDurations.parse(IsoDurations.format(duration)))
    }
  }
}
