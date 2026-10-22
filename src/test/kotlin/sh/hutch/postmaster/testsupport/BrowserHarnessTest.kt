package sh.hutch.postmaster.testsupport

import com.microsoft.playwright.options.ColorScheme
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The browser test harness works (REQ Technical choices: "The user interface
// should be tested thoroughly"): pages open under the /mail root path, and the
// colour scheme and viewport can be chosen.
@QuarkusTest
class BrowserHarnessTest : BrowserTest() {
  @Test
  fun `opens pages under the root path`() {
    val page = visit("q/openapi?format=json")

    assertTrue(page.url().contains("/mail/q/openapi"), page.url())
    assertTrue(page.content().contains("openapi"), "page shows the OpenAPI document")
  }

  @Test
  fun `requests outside the application are blocked and recorded`() {
    // The guard every browser test relies on (REQ User interface style; CLAR S4: nothing from a CDN)
    val page = visit("q/openapi?format=json")
    page.setContent(
        "<img src='https://cdn.example.com/logo.png'><link rel='stylesheet' href='https://fonts.example.com/a.css'>"
    )
    page.waitForLoadState()

    assertEquals(
        setOf("https://cdn.example.com/logo.png", "https://fonts.example.com/a.css"),
        externalRequests.toSet()
    )
    externalRequests.clear()
  }

  @Test
  fun `pages can be opened in dark mode at phone width`() {
    val page = openPage(colorScheme = ColorScheme.DARK, width = 390, height = 844)
    page.navigate("q/openapi?format=json")

    assertEquals(true, page.evaluate("matchMedia('(prefers-color-scheme: dark)').matches"))
    assertEquals(390, page.evaluate("window.innerWidth"))
  }
}
