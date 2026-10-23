package sh.hutch.postmaster.ui

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import sh.hutch.postmaster.fixtures.FixtureRow

/**
 * The table component (REQ User interface style): "The header row for tables
 * must be sticky when they are too big to fit in the viewport ... Table
 * columns can be resized and when text overflows the width then it is
 * truncated. Hovering over that text will bring up a tool tip with the full
 * contents. Tables can reduce the number of columns to be reactive" (CLAR
 * D12: lowest-priority columns hide first). The layout works on phones with
 * no sideways scrolling.
 */
@QuarkusTest
class TableBrowserTest : FixtureBrowserTest() {
  @Test
  fun `the header stays in view while a long table scrolls`() {
    val page = fixture()
    val headerTop = page.testId("th-name").boundingBox()!!.y
    assertTrue(page.evaluate("() => document.documentElement.scrollHeight > window.innerHeight * 2") as Boolean,
        "the table is taller than the screen")

    page.evaluate("y => window.scrollTo(0, y)", headerTop + 900)
    page.waitForFunction("y => window.scrollY >= y", headerTop + 900)

    val header = page.testId("th-name").boundingBox()!!
    assertTrue(header.y >= -0.5 && header.y < 2.0, "the header is at the top of the screen: y=${header.y}")
    val firstRow = page.locator("[data-testid=fixture-row]").first().boundingBox()!!
    assertTrue(firstRow.y < 0, "the first rows have scrolled away: y=${firstRow.y}")
    assertThat(page.testId("th-subject")).isInViewport()
  }

  @Test
  fun `dragging a column edge resizes it and its neighbour`() {
    val page = fixture()
    val tableWidth = page.testId("fixture-table").boundingBox()!!.width
    val name = width(page, "th-name")
    val sender = width(page, "th-sender")

    drag(page, "resize-name", 80.0)

    assertEquals(name + 80, width(page, "th-name"), 4.0, "the column grows")
    assertEquals(sender - 80, width(page, "th-sender"), 4.0, "its neighbour shrinks")
    assertEquals(tableWidth, page.testId("fixture-table").boundingBox()!!.width, 1.0, "the table keeps its width")
  }

  @Test
  fun `a column can't be squeezed to nothing`() {
    val page = fixture()

    drag(page, "resize-name", -2000.0)

    assertTrue(width(page, "th-name") >= 47.0, "name column width ${width(page, "th-name")}")
  }

  @Test
  fun `a column can be resized from the keyboard`() {
    val page = fixture()
    val name = width(page, "th-name")

    page.testId("resize-name").focus()
    page.keyboard().press("ArrowRight")

    assertEquals(name + 16, width(page, "th-name"), 3.0)
  }

  @Test
  fun `text too long for its column is truncated with an ellipsis`() {
    val page = fixture()
    val cell = page.locator("[data-testid=fixture-row]").first().locator("[data-testid=cell-subject]")

    assertTrue(cell.evaluate("e => e.scrollWidth > e.clientWidth") as Boolean, "the subject overflows its cell")
    assertEquals("ellipsis", cell.evaluate("e => getComputedStyle(e).textOverflow"))
    assertEquals("nowrap", cell.evaluate("e => getComputedStyle(e).whiteSpace"))
    val box = cell.boundingBox()!!
    val row = page.locator("[data-testid=fixture-row]").first().boundingBox()!!
    assertTrue(box.height <= row.height, "the cell stays on one line")
  }

  @Test
  fun `hovering over truncated text shows the full text as a tooltip`() {
    val page = fixture()
    val cell = page.locator("[data-testid=fixture-row]").first().locator("[data-testid=cell-subject]")

    cell.hover()

    assertThat(cell).hasAttribute("title", FixtureRow.LONG_SUBJECT)
  }

  @Test
  fun `text that fits gets no tooltip`() {
    val page = fixture()
    val cell = page.locator("[data-testid=fixture-row]").nth(2).locator("[data-testid=cell-name]")

    cell.hover()

    assertNull(cell.getAttribute("title"))
  }

  @Test
  fun `a tooltip set by the page is kept`() {
    val page = fixture()
    val cell = page.locator("[data-testid=fixture-row]").nth(1).locator("[data-testid=cell-notes]")

    cell.hover()

    assertThat(cell).hasAttribute("title", "Explicit tooltip for row 2")
  }

  @ParameterizedTest(name = "viewport {0}px")
  @ValueSource(ints = [320, 360, 390, 500, 700, 900, 1100, 1280])
  fun `low-priority columns hide first as the table narrows`(viewport: Int) {
    // CLAR D12 and docs/dev/contracts.md: priority 2 hides below 480px of
    // table width, 3 below 640px, 4 below 832px, 5 below 1024px; 1 never
    val page = fixture(width = viewport)
    val wrapWidth = page.testId("fixture-table-wrap").boundingBox()!!.width

    val thresholds = mapOf(1 to 0.0, 2 to 480.0, 3 to 640.0, 4 to 832.0, 5 to 1024.0)
    val priorities = mapOf("name" to 1, "sender" to 2, "subject" to 3, "received" to 4, "notes" to 5, "actions" to 1)
    val firstRow = page.locator("[data-testid=fixture-row]").first()
    for ((col, priority) in priorities) {
      val expected = wrapWidth >= thresholds.getValue(priority)
      assertEquals(expected, page.testId("th-$col").isVisible, "$col header at table width $wrapWidth")
      val cell = firstRow.locator("[data-testid=cell-$col]")
      assertEquals(expected, cell.isVisible, "$col cells at table width $wrapWidth")
    }
  }

  @ParameterizedTest(name = "viewport {0}px")
  @ValueSource(ints = [320, 360, 390])
  fun `phone screens never scroll sideways`(viewport: Int) {
    // REQ User interface style: responsive, and works well on a mobile screen
    val page = fixture(width = viewport, height = 740)

    assertFalse(page.scrollsSideways(), "page scrollWidth fits $viewport px")
    page.testId("menu-button").click()
    assertFalse(page.scrollsSideways(), "with the menu open")
  }

  private fun width(page: Page, testId: String): Double = page.testId(testId).boundingBox()!!.width

  private fun drag(page: Page, handle: String, dx: Double) {
    val box = page.testId(handle).boundingBox()!!
    val x = box.x + box.width / 2
    val y = box.y + box.height / 2
    page.mouse().move(x, y)
    page.mouse().down()
    page.mouse().move(x + dx / 2, y, com.microsoft.playwright.Mouse.MoveOptions().setSteps(5))
    page.mouse().move(x + dx, y, com.microsoft.playwright.Mouse.MoveOptions().setSteps(5))
    page.mouse().up()
  }
}
