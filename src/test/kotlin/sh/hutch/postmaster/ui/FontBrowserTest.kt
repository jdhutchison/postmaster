package sh.hutch.postmaster.ui

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// REQ User interface style: "Atkinson Hyperledgible Next is to be used for the
// font" (Atkinson Hyperlegible Next), served by the application (CLAR S4)
@QuarkusTest
class FontBrowserTest : FixtureBrowserTest() {
  @Test
  fun `text is set in Atkinson Hyperlegible Next, loaded from the application`() {
    val page = fixture()
    page.evaluate("() => document.fonts.ready")

    val family = page.style("body", "font-family")
    assertTrue(family.trim().startsWith("\"Atkinson Hyperlegible Next\""), "body font-family: $family")
    assertEquals(true, page.evaluate("() => document.fonts.check('16px \"Atkinson Hyperlegible Next\"')"))
    val loaded = page.evaluate(
        "() => [...document.fonts].filter(f => f.family.replace(/\"/g, '') === 'Atkinson Hyperlegible Next' " +
            "&& f.status === 'loaded').length"
    ) as Int
    assertTrue(loaded > 0, "a font face of the family has loaded")
    val fontRequests = requests.filter { it.contains(".woff2") }
    assertTrue(fontRequests.isNotEmpty(), "the font file was requested")
    for (url in fontRequests) {
      assertTrue(url.contains("/mail/assets/fonts/atkinson-hyperlegible-next/"), "font from the app: $url")
    }
  }

  @Test
  fun `bold text uses the same family`() {
    // A variable font: bold text must not fall back to another family
    val page = fixture()
    page.evaluate("() => document.fonts.ready")

    assertEquals(true, page.evaluate("() => document.fonts.check('bold 16px \"Atkinson Hyperlegible Next\"')"))
    assertTrue(page.style("[data-testid=page-title]", "font-family").contains("Atkinson Hyperlegible Next"))
  }
}
