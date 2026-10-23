package sh.hutch.postmaster.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.util.RelativeTime
import java.time.Duration
import java.time.Instant

/**
 * Relative times stay right in the page (REQ User interface style, relative
 * timestamps): postmaster.js recomputes them with the same rules as the
 * server, and the absolute time is the tooltip (CLAR D10).
 */
@QuarkusTest
class RelativeTimeBrowserTest : FixtureBrowserTest() {
  @Test
  fun `the page uses the same rules as the server`() {
    val page = fixture()
    val now = Instant.parse("2026-10-23T06:40:00Z")

    for (elapsed in listOf(
        "PT0S", "PT59S", "PT60S", "PT14M", "PT59M59S", "PT60M", "PT23H59M59S", "PT24H", "P2D", "P45D", "P400D"
    )) {
      val then = now.minus(Duration.parse(elapsed))
      val inPage = page.evaluate(
          "([t, n]) => Postmaster.relativeTime(new Date(t), new Date(n))",
          listOf(then.toEpochMilli().toDouble(), now.toEpochMilli().toDouble())
      )
      assertEquals(RelativeTime.format(then, now), inPage, "$elapsed ago")
    }
    val future = page.evaluate(
        "([t, n]) => Postmaster.relativeTime(new Date(t), new Date(n))",
        listOf(now.plusSeconds(300).toEpochMilli().toDouble(), now.toEpochMilli().toDouble())
    )
    assertEquals("just now", future)
  }

  @Test
  fun `a relative time carries its absolute time as a tooltip`() {
    val page = fixture()
    val time = page.locator("[data-testid=time-minutes] time")

    assertThat(time).hasText("14m ago")
    time.hover()
    val absolute = Regex("^\\w{3} \\d{1,2} \\w{3} \\d{4}, \\d{2}:\\d{2} AE[DS]T$")
    assertEquals(true, (time.getAttribute("title") ?: "").matches(absolute))
  }
}
