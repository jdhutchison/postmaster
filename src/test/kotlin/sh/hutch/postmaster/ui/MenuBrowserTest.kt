package sh.hutch.postmaster.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * REQ User interface style: "All menu options must be in a single menu that
 * is accessible by clicking a hamburger menu button. The background of that
 * button should be frosted glass so it 'floats' over the rest of the
 * contents." The menu also works from the keyboard.
 */
@QuarkusTest
class MenuBrowserTest : FixtureBrowserTest() {
  @Test
  fun `the menu starts closed`() {
    val page = fixture()

    assertThat(page.testId("menu")).isHidden()
    assertThat(page.testId("menu-button")).hasAttribute("aria-expanded", "false")
    assertThat(page.testId("menu-button")).isVisible()
  }

  @Test
  fun `clicking the button opens and closes the menu with every option`() {
    val page = fixture()

    page.testId("menu-button").click()

    assertThat(page.testId("menu")).isVisible()
    assertThat(page.testId("menu-button")).hasAttribute("aria-expanded", "true")
    for (item in ITEMS) {
      assertThat(page.testId(item)).isVisible()
    }

    page.testId("menu-button").click()

    assertThat(page.testId("menu")).isHidden()
    assertThat(page.testId("menu-button")).hasAttribute("aria-expanded", "false")
  }

  @Test
  fun `a menu item goes to its page`() {
    val page = fixture()

    page.testId("menu-button").click()
    page.testId("menu-dashboard").click()

    page.waitForURL("**/mail/")
    assertThat(page.testId("home-page")).isVisible()
  }

  @Test
  fun `the keyboard opens the menu, moves through it and closes it`() {
    val page = fixture()
    page.testId("menu-button").focus()

    page.keyboard().press("Enter")
    assertThat(page.testId("menu")).isVisible()
    assertThat(page.testId("menu-dashboard")).isFocused()

    page.keyboard().press("ArrowDown")
    assertThat(page.testId("menu-emails")).isFocused()
    page.keyboard().press("End")
    assertThat(page.testId("menu-configuration")).isFocused()
    page.keyboard().press("Home")
    assertThat(page.testId("menu-dashboard")).isFocused()
    // Up and Down wrap at the ends (WAI-ARIA menu pattern; docs/dev/contracts.md)
    page.keyboard().press("ArrowUp")
    assertThat(page.testId("menu-configuration")).isFocused()
    page.keyboard().press("ArrowDown")
    assertThat(page.testId("menu-dashboard")).isFocused()

    page.keyboard().press("Escape")
    assertThat(page.testId("menu")).isHidden()
    assertThat(page.testId("menu-button")).isFocused()
    assertThat(page.testId("menu-button")).hasAttribute("aria-expanded", "false")

    page.keyboard().press("Space")
    assertThat(page.testId("menu")).isVisible()
  }

  @Test
  fun `clicking outside the menu closes it`() {
    val page = fixture()
    page.testId("menu-button").click()

    page.testId("page-title").click()

    assertThat(page.testId("menu")).isHidden()
  }

  @Test
  fun `tabbing past the last option closes the menu`() {
    val page = fixture()
    page.testId("menu-button").click()
    page.keyboard().press("End")

    page.keyboard().press("Tab")

    assertThat(page.testId("menu")).isHidden()
  }

  @Test
  fun `the button and the open menu have a frosted glass background`() {
    val page = fixture()
    page.testId("menu-button").click()

    for (selector in listOf("[data-testid=menu-button]", "[data-testid=menu]")) {
      val filter = page.style(selector, "backdrop-filter")
      assertTrue(filter.contains("blur("), "$selector backdrop-filter: $filter")
      val background = page.style(selector, "background-color")
      assertTrue(alpha(background) < 1.0, "$selector background is translucent: $background")
    }
  }

  @Test
  fun `the button floats over the content as the page scrolls`() {
    val page = fixture()
    val before = page.testId("menu-button").boundingBox()!!

    page.evaluate("() => window.scrollTo(0, 1500)")
    page.waitForFunction("() => window.scrollY >= 1500")

    assertEquals("fixed", page.style("[data-testid=menu-button]", "position"))
    val after = page.testId("menu-button").boundingBox()!!
    assertEquals(before.y, after.y, 0.5, "the button stays where it was on screen")
    val onTop = page.evaluate(
        """() => {
          const button = document.querySelector('[data-testid=menu-button]');
          const box = button.getBoundingClientRect();
          const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
          return button === hit || button.contains(hit);
        }"""
    )
    assertEquals(true, onTop, "the button is on top of the content under it")
  }

  @Test
  fun `the open menu floats over the content too`() {
    val page = fixture()
    page.evaluate("() => window.scrollTo(0, 1500)")
    page.testId("menu-button").click()

    assertEquals("fixed", page.style("[data-testid=menu]", "position"))
    val onTop = page.evaluate(
        """() => {
          const item = document.querySelector('[data-testid=menu-configuration]');
          const box = item.getBoundingClientRect();
          const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
          return item === hit || item.contains(hit);
        }"""
    )
    assertEquals(true, onTop, "menu items are on top of the content")
  }

  @Test
  fun `the menu fits a phone screen`() {
    // REQ User interface style: work as well as possible on a mobile screen
    val page = fixture(width = 360, height = 740)
    page.testId("menu-button").click()

    val box = page.testId("menu").boundingBox()!!
    assertTrue(box.x >= 0 && box.x + box.width <= 360, "menu within the screen: $box")
    assertFalse(page.scrollsSideways())
    for (item in ITEMS) {
      assertThat(page.testId(item)).isInViewport()
    }
  }

  /** The alpha of a computed colour: `rgba(r, g, b, a)`, `rgb(r g b / a)` or `color(srgb r g b / a)`; 1.0 if opaque */
  private fun alpha(colour: String): Double {
    val inner = colour.substringAfter("(").substringBeforeLast(")")
    if ("/" in inner) {
      val value = inner.substringAfter("/").trim()
      return if (value.endsWith("%")) value.removeSuffix("%").toDouble() / 100 else value.toDouble()
    }
    val parts = inner.split(",").map { it.trim() }
    return if (parts.size == 4) parts[3].toDouble() else 1.0
  }

  companion object {
    val ITEMS = listOf(
        "menu-dashboard", "menu-emails", "menu-types", "menu-calendars", "menu-prompts",
        "menu-rules-classification", "menu-rules-action", "menu-configuration"
    )
  }
}
