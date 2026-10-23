package sh.hutch.postmaster.ui

import com.microsoft.playwright.options.ColorScheme
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import sh.hutch.postmaster.testsupport.Palettes
import sh.hutch.postmaster.testsupport.Palettes.rgb

/**
 * Light and dark mode follow the device (REQ User interface style: "The
 * palette should have light and dark modes and the mode must match the
 * system mode of the device"), using Alucard for light and Dracula for dark
 * (CLAR Q18), with the colours of https://draculatheme.com/spec.
 */
@QuarkusTest
class ThemeBrowserTest : FixtureBrowserTest() {
  @ParameterizedTest
  @EnumSource(value = ColorScheme::class, names = ["LIGHT", "DARK"])
  fun `the page background and text use the palette for the system mode`(scheme: ColorScheme) {
    val palette = palette(scheme)
    val page = fixture(colorScheme = scheme)

    assertEquals(rgb(palette.getValue("background")), page.style("body", "background-color"))
    assertEquals(rgb(palette.getValue("foreground")), page.style("body", "color"))
    assertEquals(if (scheme == ColorScheme.DARK) "dark" else "light", page.style(":root", "color-scheme").trim())
  }

  @ParameterizedTest
  @EnumSource(value = ColorScheme::class, names = ["LIGHT", "DARK"])
  fun `every palette colour is the spec's colour for the system mode`(scheme: ColorScheme) {
    val palette = palette(scheme)
    val page = fixture(colorScheme = scheme)

    val tokens = mapOf(
        "--pm-background" to "background", "--pm-current-line" to "current-line", "--pm-selection" to "selection",
        "--pm-foreground" to "foreground", "--pm-comment" to "comment", "--pm-red" to "red",
        "--pm-orange" to "orange", "--pm-yellow" to "yellow", "--pm-green" to "green", "--pm-cyan" to "cyan",
        "--pm-purple" to "purple", "--pm-pink" to "pink", "--pm-bg-floating" to "floating",
        "--pm-bg-lighter" to "background-lighter", "--pm-bg-light" to "background-light",
        "--pm-bg-dark" to "background-dark", "--pm-bg-darker" to "background-darker"
    )
    for ((property, specName) in tokens) {
      assertEquals(rgb(palette.getValue(specName)), page.resolvedColour(property), "$property ($specName)")
    }
    assertEquals(rgb(Palettes.FOCUS), page.resolvedColour("--pm-focus"), "--pm-focus (Functional Purple)")
  }

  @ParameterizedTest
  @EnumSource(value = ColorScheme::class, names = ["LIGHT", "DARK"])
  fun `links and table headers use the palette`(scheme: ColorScheme) {
    val palette = palette(scheme)
    val page = fixture(colorScheme = scheme)

    assertEquals(rgb(palette.getValue("cyan")), page.style("[data-testid=plain-link]", "color"))
    assertEquals(rgb(palette.getValue("background-light")), page.style("[data-testid=th-name]", "background-color"))
  }

  @ParameterizedTest
  @EnumSource(value = ColorScheme::class, names = ["LIGHT", "DARK"])
  fun `keyboard focus is shown in the spec's focus colour`(scheme: ColorScheme) {
    val page = fixture(colorScheme = scheme)

    page.testId("plain-link").focus()
    page.keyboard().press("Shift+Tab")
    page.keyboard().press("Tab")

    assertEquals(rgb(Palettes.FOCUS), page.style("[data-testid=plain-link]", "outline-color"))
  }

  private fun palette(scheme: ColorScheme) = if (scheme == ColorScheme.DARK) Palettes.DRACULA else Palettes.ALUCARD
}
