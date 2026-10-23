package sh.hutch.postmaster.ui

import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import sh.hutch.postmaster.testsupport.BrowserTest

/** Browser tests against the UI fixture page (sh.hutch.postmaster.fixtures.UiFixtureResource) */
abstract class FixtureBrowserTest : BrowserTest() {
  /** Opens the fixture page, waiting until it has loaded and postmaster.js is ready */
  fun fixture(
      query: String = "",
      colorScheme: ColorScheme = ColorScheme.LIGHT,
      width: Int = 1280,
      height: Int = 800
  ): Page {
    val page = openPage(colorScheme, width, height)
    val problems = mutableListOf<String>()
    page.onConsoleMessage { if (it.type() == "error") problems += "console: ${it.text()}" }
    page.onPageError { problems += "page error: $it" }
    page.onRequestFailed { problems += "failed request: ${it.url()} ${it.failure()}" }
    val response = page.navigate("test-fixtures/ui$query")
    if (response == null || response.status() != 200) {
      throw AssertionError("the fixture page failed: HTTP ${response?.status()}\n${response?.text()?.take(2000)}")
    }
    page.waitForLoadState(LoadState.NETWORKIDLE)
    try {
      page.waitForFunction(
          "() => window.Postmaster !== undefined && window.htmx !== undefined",
          null,
          Page.WaitForFunctionOptions().setTimeout(10_000.0)
      )
    } catch (e: PlaywrightException) {
      throw AssertionError("postmaster.js or HTMX didn't load on the fixture page: $problems", e)
    }
    return page
  }

  fun Page.testId(id: String) = locator("[data-testid=\"$id\"]")

  /** A computed style property of the first element matching [selector] */
  fun Page.style(selector: String, property: String): String =
      evaluate(
          "([s, p]) => getComputedStyle(document.querySelector(s)).getPropertyValue(p)",
          listOf(selector, property)
      ) as String

  /** The colour a CSS custom property resolves to, as a computed rgb() string */
  fun Page.resolvedColour(property: String): String =
      evaluate(
          """p => {
            const probe = document.createElement('span');
            probe.style.color = 'var(' + p + ')';
            document.body.appendChild(probe);
            const colour = getComputedStyle(probe).color;
            probe.remove();
            return colour;
          }""",
          property
      ) as String

  /** Whether the page can scroll sideways */
  fun Page.scrollsSideways(): Boolean =
      evaluate("() => document.documentElement.scrollWidth > window.innerWidth") as Boolean
}
