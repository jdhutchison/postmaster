package sh.hutch.postmaster.ui

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.controllers.TimeDisplay
import sh.hutch.postmaster.testsupport.getHtml
import java.time.Instant

/**
 * Relative times with the absolute time as a tooltip (REQ User interface
 * style, relative timestamps; CLAR D10: "Times are shown as relative values,
 * with the absolute time in a tooltip"), in the processing.timezone setting
 * (CLAR D8, default Australia/Melbourne).
 */
@QuarkusTest
class TimeDisplayTest {
  @Inject
  lateinit var times: TimeDisplay

  @Test
  fun `the absolute time is in the configured time zone`() {
    // 19:40 UTC on Thursday 22 October is 06:40 on Friday 23 October in Melbourne (AEDT, UTC+11)
    assertEquals("Fri 23 Oct 2026, 06:40 AEDT", times.absolute(Instant.parse("2026-10-22T19:40:00Z")))
    // Outside daylight saving: AEST, UTC+10
    assertEquals("Wed 1 Jul 2026, 10:00 AEST", times.absolute(Instant.parse("2026-07-01T00:00:00Z")))
  }

  @Test
  fun `the machine-readable time is UTC ISO 8601`() {
    assertEquals("2026-10-22T19:40:00Z", times.iso(Instant.parse("2026-10-22T19:40:00Z")))
  }

  @Test
  fun `the relative time is measured from now`() {
    assertEquals("14m ago", times.relative(Instant.now().minusSeconds(14 * 60 + 5)))
  }

  @Test
  fun `the time tag shows the relative time with the absolute time as its tooltip`() {
    val page = getHtml("/test-fixtures/ui")

    val expected = mapOf(
        "seconds" to "just now", "minutes" to "14m ago", "hours" to "5h ago", "days" to "2d ago",
        "many-days" to "45d ago"
    )
    for ((key, text) in expected) {
      val time = page.select("[data-testid=time-$key] time").single()
      assertEquals(text, time.text(), key)
      assertTrue(time.hasAttr("data-pm-relative"), "$key is refreshed in the page")
      val instant = Instant.parse(time.attr("datetime"))
      assertEquals(times.absolute(instant), time.attr("title"), "$key tooltip")
      assertTrue(ABSOLUTE.matches(time.attr("title")), "$key tooltip format: ${time.attr("title")}")
    }
  }

  companion object {
    private val ABSOLUTE = Regex("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) \\d{1,2} [A-Z][a-z]{2} \\d{4}, \\d{2}:\\d{2} AE[DS]T$")
  }
}
