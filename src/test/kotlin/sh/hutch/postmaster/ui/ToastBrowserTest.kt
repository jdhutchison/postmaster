package sh.hutch.postmaster.ui

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.WaitForSelectorState
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import sh.hutch.postmaster.testsupport.Palettes
import sh.hutch.postmaster.testsupport.Palettes.rgb

/**
 * REQ User interface style: "Toasts are to be used to communicate successful
 * changes or errors. Success confirmation toasts will fade after an
 * appropriate time, error message toasts must be explicitly dismissed or
 * closed." Toasts can come with a full page load or an HTMX response.
 */
@QuarkusTest
class ToastBrowserTest : FixtureBrowserTest() {
  @Test
  fun `a success toast on a page load fades away by itself`() {
    val page = fixture("?toast=success&message=Saved%20on%20load")

    assertFadesByItself(page, page.toast("success"), "Saved on load")
  }

  @Test
  fun `a success toast from an HTMX response fades away by itself`() {
    val page = fixture()

    page.testId("htmx-success").click()

    assertFadesByItself(page, page.toast("success"), "Saved by HTMX")
  }

  @Test
  fun `error toasts stay until they are dismissed`() {
    val page = fixture("?toast=error&message=Failed%20on%20load")
    page.testId("htmx-error").click()
    val onLoad = page.toast("error").filter(Locator.FilterOptions().setHasText("Failed on load"))
    val fromHtmx = page.toast("error").filter(Locator.FilterOptions().setHasText("Failed by HTMX"))
    assertThat(onLoad).isVisible()
    assertThat(fromHtmx).isVisible()

    // Well past the time a success toast takes to go
    page.waitForTimeout(6500.0)
    assertThat(onLoad).isVisible()
    assertThat(fromHtmx).isVisible()

    onLoad.locator("[data-testid=toast-close]").click()
    assertThat(onLoad).hasCount(0)
    assertThat(fromHtmx).isVisible()
    fromHtmx.locator("[data-testid=toast-close]").click()
    assertThat(fromHtmx).hasCount(0)
  }

  @Test
  fun `a failed HTMX request is reported in an error toast`() {
    val page = fixture()

    page.testId("htmx-fail").click()

    val toast = page.toast("error")
    assertThat(toast).isVisible()
    assertThat(toast.locator("[data-testid=toast-message]")).hasText("Request failed (500)")
  }

  @Test
  fun `a success toast can be dismissed straight away`() {
    val page = fixture("?toast=success&message=Dismiss%20me")

    page.toast("success").locator("[data-testid=toast-close]").click()

    assertThat(page.toast("success")).hasCount(0)
  }

  @Test
  fun `toasts are announced to assistive technology`() {
    val page = fixture("?toast=error&message=Broken")
    page.testId("htmx-success").click()

    assertThat(page.toast("error")).hasAttribute("role", "alert")
    assertThat(page.toast("success")).hasAttribute("role", "status")
  }

  @ParameterizedTest
  @EnumSource(value = ColorScheme::class, names = ["LIGHT", "DARK"])
  fun `success toasts are marked green and errors red`(scheme: ColorScheme) {
    // CLAR Q18 palette; the colour tells success and failure apart at a glance
    val palette = if (scheme == ColorScheme.DARK) Palettes.DRACULA else Palettes.ALUCARD
    val page = fixture("?toast=error&message=Broken", colorScheme = scheme)
    page.testId("htmx-success").click()
    assertThat(page.toast("success")).isVisible()

    val success = page.style("[data-testid=toast][data-level=success]", "border-left-color")
    val error = page.style("[data-testid=toast][data-level=error]", "border-left-color")
    assertEquals(rgb(palette.getValue("green")), success)
    assertEquals(rgb(palette.getValue("red")), error)
  }

  @Test
  fun `toasts fit a phone screen`() {
    val page = fixture("?toast=error&message=" + "A%20long%20error%20message%20".repeat(6), width = 360, height = 740)

    val box = page.toast("error").boundingBox()!!
    assertTrue(box.x >= 0 && box.x + box.width <= 360, "toast within the screen: $box")
    assertEquals(false, page.scrollsSideways())
  }

  private fun Page.toast(level: String): Locator = locator("[data-testid=toast][data-level=$level]")

  /**
   * The toast shows [message], is still there a few seconds later (an
   * "appropriate time" to read it), then goes without anyone touching it.
   */
  private fun assertFadesByItself(page: Page, toast: Locator, message: String) {
    assertThat(toast).isVisible()
    assertThat(toast.locator("[data-testid=toast-message]")).hasText(message)
    val shown = System.nanoTime()

    page.waitForTimeout(2500.0)
    assertThat(toast).isVisible()

    toast.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout(8000.0))
    val seconds = (System.nanoTime() - shown) / 1e9
    assertTrue(seconds < 8.0, "gone after ${"%.1f".format(seconds)}s")
  }
}
