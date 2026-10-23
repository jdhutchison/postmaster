package sh.hutch.postmaster.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.fixtures.FixtureActions

/**
 * Modal dialogs and confirmation (REQ Types 3, 4, 7; Prompts 4; Rules 4:
 * "a pop-up dialog will ask the user to confirm the deletion"; Viewing an
 * individual email 3, 4: a pop-up with a cancel button that takes no action).
 */
@QuarkusTest
class DialogBrowserTest : FixtureBrowserTest() {
  @Inject
  lateinit var actions: FixtureActions

  @Test
  fun `a modal dialog opens over the page and its close button closes it`() {
    val page = fixture()

    page.testId("open-dialog").click()

    val dialog = page.testId("fixture-dialog")
    assertThat(dialog).isVisible()
    assertEquals(true, page.evaluate("() => document.getElementById('fixture-dialog').matches(':modal')"))
    assertThat(page.testId("dialog-input")).isFocused()

    dialog.locator("[data-testid=dialog-close]").click()

    assertThat(dialog).isHidden()
    assertThat(page.testId("open-dialog")).isFocused()
  }

  @Test
  fun `a cancel button inside a dialog closes it`() {
    val page = fixture()
    page.testId("open-dialog").click()

    page.testId("dialog-cancel").click()

    assertThat(page.testId("fixture-dialog")).isHidden()
  }

  @Test
  fun `Escape closes a dialog`() {
    val page = fixture()
    page.testId("open-dialog").click()

    page.keyboard().press("Escape")

    assertThat(page.testId("fixture-dialog")).isHidden()
    assertThat(page.testId("open-dialog")).isFocused()
  }

  @Test
  fun `clicking outside a dialog closes it`() {
    val page = fixture()
    page.testId("open-dialog").click()
    val box = page.testId("fixture-dialog").boundingBox()!!

    // A point on the backdrop, well away from the dialog box
    page.mouse().click(5.0, 795.0)

    assertTrue(box.y > 5 || box.x > 5, "the click point is outside the dialog: $box")
    assertThat(page.testId("fixture-dialog")).isHidden()
  }

  @Test
  fun `HTMX can load a dialog's content and open it`() {
    val page = fixture()

    page.testId("load-dialog").click()

    assertThat(page.testId("loaded-dialog")).isVisible()
    assertThat(page.testId("loaded-content")).hasText("Loaded by HTMX")
  }

  @Test
  fun `an HTMX action asks for confirmation and doesn't run when cancelled`() {
    val page = fixture()
    val before = actions.count()

    page.testId("confirm-htmx").click()

    val confirm = page.testId("confirm-dialog")
    assertThat(confirm).isVisible()
    assertThat(page.testId("confirm-message")).hasText("Delete fixture thing?")
    assertThat(page.testId("confirm-ok")).hasText("Delete")
    assertThat(page.testId("confirm-cancel")).isFocused()

    page.testId("confirm-cancel").click()

    assertThat(confirm).isHidden()
    page.waitForTimeout(500.0)
    assertEquals(before, actions.count(), "the action didn't run")
    assertTrue(requests.none { it.contains("/test-fixtures/ui/action") }, "no request was sent: $requests")
    assertThat(page.testId("action-result")).isEmpty()
  }

  @Test
  fun `an HTMX action runs once it is confirmed`() {
    val page = fixture()
    val before = actions.count()

    page.testId("confirm-htmx").click()
    page.testId("confirm-ok").click()

    assertThat(page.testId("confirm-dialog")).isHidden()
    assertThat(page.testId("action-result")).hasText("Done ${before + 1}")
    assertEquals(before + 1, actions.count())
    assertThat(page.locator("[data-testid=toast][data-level=success] [data-testid=toast-message]"))
        .hasText("Action done")
  }

  @Test
  fun `Escape cancels a confirmation`() {
    val page = fixture()
    val before = actions.count()
    page.testId("confirm-htmx").click()

    page.keyboard().press("Escape")

    assertThat(page.testId("confirm-dialog")).isHidden()
    page.waitForTimeout(500.0)
    assertEquals(before, actions.count())
  }

  @Test
  fun `a form behind a confirmation is submitted only when confirmed`() {
    val page = fixture()
    val before = actions.count()

    page.testId("confirm-form").click()
    assertThat(page.testId("confirm-message")).hasText("Submit fixture form?")
    page.testId("confirm-cancel").click()
    page.waitForTimeout(500.0)
    assertEquals(before, actions.count(), "not submitted after Cancel")

    page.testId("confirm-form").click()
    page.testId("confirm-ok").click()

    page.waitForURL("**/mail/test-fixtures/ui")
    assertThat(page.locator("[data-testid=toast][data-level=success] [data-testid=toast-message]"))
        .hasText("Form submitted")
    assertEquals(before + 1, actions.count())
  }

  @Test
  fun `a toast carried across a redirect is shown only once`() {
    val page = fixture()
    page.testId("confirm-form").click()
    page.testId("confirm-ok").click()
    page.waitForURL("**/mail/test-fixtures/ui")
    assertThat(page.locator("[data-testid=toast-message]")).hasText("Form submitted")

    page.reload()

    page.waitForFunction("() => window.Postmaster !== undefined")
    assertThat(page.locator("[data-testid=toast]")).hasCount(0)
  }

  @Test
  fun `a link behind a confirmation is followed only when confirmed`() {
    val page = fixture()

    page.testId("confirm-link").click()
    assertThat(page.testId("confirm-message")).hasText("Follow fixture link?")
    page.testId("confirm-cancel").click()
    page.waitForTimeout(500.0)
    assertTrue(!page.url().contains("followed=yes"), page.url())

    page.testId("confirm-link").click()
    page.testId("confirm-ok").click()

    page.waitForURL("**followed=yes")
    assertThat(page.testId("followed")).hasText("yes")
  }
}
