package sh.hutch.postmaster.controllers

import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.SelectOption
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.testsupport.AdminProfile

/** The admin pages in a browser (REQ Configuration, Types, Calendars, Prompts, Rules; CLAR Q9, Q10, Q17, Q19) */
@QuarkusTest
@TestProfile(AdminProfile::class)
class AdminPagesBrowserTest : AdminBrowserTest() {
  @Inject
  lateinit var settings: SettingsService

  @Inject
  lateinit var calendars: CalendarRepository

  @Inject
  lateinit var rules: RuleRepository

  @AfterEach
  fun restoreSettings() {
    settings.update("llm.model", "qwen3.8-9b-100k")
    settings.update("llm.max_input_chars", "60000")
  }

  // Configuration (REQ Configuration 2, 3)

  @Test
  fun `clicking a setting turns it into a field that saves when it loses focus`() {
    val page = open("configuration")

    page.testId("setting-value-llm.model").click()
    page.waitForHtmx()
    val input = page.testId("setting-input-llm.model")
    assertThat(input).isFocused()
    input.fill("model-from-browser")
    page.testId("page-title").click()

    assertThat(page.testId("setting-value-llm.model")).hasText("model-from-browser")
    assertThat(page.locator("[data-testid=toast][data-level=success]")).containsText("Saved LLM model")
    assertEquals("model-from-browser", settings.getString("llm.model"))
  }

  @Test
  fun `Enter saves too, and an invalid value is explained in place`() {
    val page = open("configuration")

    page.testId("setting-value-llm.max_input_chars").click()
    page.waitForHtmx()
    page.testId("setting-input-llm.max_input_chars").fill("lots")
    page.keyboard().press("Enter")
    page.waitForHtmx()

    assertThat(page.testId("setting-input-llm.max_input_chars")).hasAttribute("aria-invalid", "true")
    assertThat(page.testId("setting-error-llm.max_input_chars")).isVisible()
    assertThat(page.locator("[data-testid=toast][data-level=error]")).isVisible()
    page.testId("setting-input-llm.max_input_chars").fill("50000")
    page.keyboard().press("Enter")
    assertThat(page.testId("setting-value-llm.max_input_chars")).hasText("50000")
  }

  @Test
  fun `an encrypted setting is shown masked`() {
    // CLAR Q17
    settings.update("llm.api_key", "browser-secret")
    try {
      val page = open("configuration")

      assertThat(page.testId("setting-value-llm.api_key")).hasText(SettingsService.MASK)
      assertFalse(page.content().contains("browser-secret"))
      page.testId("setting-value-llm.api_key").click()
    page.waitForHtmx()
      assertThat(page.testId("setting-input-llm.api_key")).hasAttribute("type", "password")
      assertThat(page.testId("setting-input-llm.api_key")).hasValue("")
    } finally {
      settings.clear("llm.api_key")
    }
  }

  // Types (REQ Types 3, 6, 7, 8)

  @Test
  fun `a new type is created in a dialog, with the colour picker and hex code kept in step`() {
    val page = open("types")

    page.testId("new-type").click()
    assertThat(page.testId("type-dialog")).isVisible()
    page.waitForHtmx()
    page.locator("#type-form input[name=name]").fill("Garden")
    page.testId("colour-hex").fill("#14710a")
    page.testId("colour-hex").dispatchEvent("input")
    assertThat(page.testId("colour-picker")).hasValue("#14710a")
    page.testId("colour-picker")
        .evaluate("e => { e.value = '#a3144d'; e.dispatchEvent(new Event('input', {bubbles: true})) }")
    assertThat(page.testId("colour-hex")).hasValue("#a3144d")
    page.testId("save-type").click()

    assertThat(page.testId("type-dialog")).isHidden()
    val garden = page.locator("#types-table tr").filter(Locator.FilterOptions().setHasText("Garden"))
    assertThat(garden).hasCount(1)
    assertThat(page.locator("[data-testid=toast][data-level=success]")).containsText("Created type Garden")
  }

  @Test
  fun `a refused type keeps the dialog open with the reason`() {
    // CLAR D20
    val page = open("types")

    page.testId("new-type").click()
    page.locator("#type-form input[name=name]").fill("ebay")
    page.testId("save-type").click()

    assertThat(page.testId("type-dialog")).isVisible()
    assertThat(page.locator("#type-form [data-testid=error-name]")).isVisible()
  }

  // Calendars (REQ Calendars 3)

  @Test
  fun `the switch enables and disables a calendar and shows its state`() {
    val football = calendars.findActiveByName("Football")!!
    val page = open("calendars")
    val toggle = page.testId("enabled-calendar-${football.id}")
    assertThat(toggle).not().isChecked()

    toggle.click()

    assertThat(page.locator("[data-testid=toast][data-level=success]")).containsText("Enabled calendar Football")
    assertThat(page.testId("enabled-calendar-${football.id}")).isChecked()
    page.testId("enabled-calendar-${football.id}").click()
    assertThat(page.testId("enabled-calendar-${football.id}")).not().isChecked()
  }

  // Prompts (REQ Editing or creating a prompt 2-4; CLAR Q19)

  @Test
  fun `the prompt text area fills the screen without overflowing it`() {
    for ((width, height) in listOf(1280 to 800, 390 to 844)) {
      val page = open("prompts/new", width, height)
      val box = page.testId("prompt-contents").boundingBox()!!

      assertTrue(box.y + box.height <= height + 0.5, "the text area ends on screen at ${width}x$height: $box")
      assertTrue(box.y + box.height >= height - 80, "the text area reaches near the bottom at ${width}x$height: $box")
      assertEquals(false, page.evaluate("() => document.documentElement.scrollHeight > window.innerHeight"),
          "no page scroll")
    }
  }

  @Test
  fun `a placeholder is inserted at the cursor`() {
    val page = open("prompts/new")
    val text = page.testId("prompt-contents")
    text.fill("Sender: \nmore")
    text.evaluate("e => { e.focus(); e.setSelectionRange(8, 8) }")

    page.testId("placeholder-select").selectOption("{{sender}}")
    page.testId("insert-placeholder").click()

    assertThat(text).hasValue("Sender: {{sender}}\nmore")
    assertEquals(18, text.evaluate("e => e.selectionStart"))
  }

  @Test
  fun `the preview shows the rendered markdown`() {
    val page = open("prompts/new")
    page.testId("prompt-contents").fill("# Title\n\n**bold**")

    page.testId("preview-toggle").click()

    assertThat(page.testId("prompt-preview").locator("h1")).hasText("Title")
    assertThat(page.testId("prompt-preview").locator("strong")).hasText("bold")
    assertThat(page.testId("preview-toggle")).hasAttribute("aria-pressed", "true")
    page.testId("preview-toggle").click()
    assertThat(page.testId("prompt-contents")).isVisible()
  }

  // Rules (REQ Rules 6, Creating a new rule 4-10; CLAR Q9, Q10)

  @Test
  fun `only the match types that apply to the attribute can be chosen`() {
    val page = open("rules/classification/new")
    val row = page.testId("condition-row").first()

    row.locator("select[name$=attribute]").selectOption("events")
    val values = row.locator("select[name$=matchType]").evaluate(
        "s => [...s.options].filter(o => !o.disabled && !o.hidden).map(o => o.value)"
    )
    assertEquals(listOf("regex", "has", "is_present"), values)

    row.locator("select[name$=matchType]").selectOption("has")
    assertThat(row.locator("input[name$=test]")).isHidden()
    row.locator("select[name$=matchType]").selectOption("regex")
    assertThat(row.locator("input[name$=test]")).isVisible()
  }

  @Test
  fun `each action type shows the fields it needs`() {
    // REQ Creating a new rule 10, 11; CLAR Q16
    val page = open("rules/action/new")
    val row = page.testId("action-row").first()
    val type = row.locator("select[name$=actionType]")

    type.selectOption("move")
    assertThat(row.locator("input[name$=destination]")).isVisible()
    assertThat(row.locator("select[name$=typeId]")).isHidden()
    type.selectOption("set_type")
    assertThat(row.locator("select[name$=typeId]")).isVisible()
    assertThat(row.locator("input[name$=destination]")).isHidden()
    type.selectOption("process_bill")
    assertThat(row.locator("select[name$=calendarId]")).isVisible()
    type.selectOption("create_events")
    assertThat(row.locator("input[name$=notifications]")).isVisible()
  }

  @Test
  fun `conditions and actions can be added and removed, keeping at least one of each`() {
    // REQ Creating a new rule 4, 8
    val page = open("rules/classification/new")

    assertThat(page.testId("condition-row").first().locator("[data-testid=remove-condition]")).isDisabled()
    page.testId("add-condition").click()
    assertThat(page.testId("condition-row")).hasCount(2)
    page.testId("condition-row").nth(1).locator("[data-testid=remove-condition]").click()
    assertThat(page.testId("condition-row")).hasCount(1)
    page.testId("add-action").click()
    assertThat(page.testId("action-row")).hasCount(2)
    assertThat(page.testId("action-row").first().locator("[data-testid=remove-action]")).isEnabled()
  }

  @Test
  fun `a rule is created in the browser and appears in its list`() {
    val page = open("rules/action/new")
    page.locator("#rule-form input[name=name]").fill("Browser rule")
    page.locator("#rule-form textarea[name=description]").fill("Made in a test")
    val condition = page.testId("condition-row").first()
    condition.locator("select[name$=attribute]").selectOption("subject")
    condition.locator("select[name$=matchType]").selectOption("contains")
    condition.locator("input[name$=test]").fill("hello")
    page.testId("action-row").first().locator("select[name$=actionType]").selectOption(SelectOption()
        .setValue("mark_read"))

    page.testId("save-rule").click()

    page.waitForURL("**/mail/rules/action")
    assertThat(page.locator("[data-testid=toast-message]")).containsText("Created rule Browser rule")
    assertTrue(rules.findActiveByName(RuleType.ACTION, "Browser rule") != null)
  }

  @Test
  fun `the enabled switch on the rule list shows and changes the rule's state`() {
    // REQ Rules 6
    val rule = rules.findActiveByName(RuleType.ACTION, "School events")!!
    val page = open("rules/action")
    assertThat(page.testId("enabled-rule-${rule.id}")).isChecked()

    page.testId("enabled-rule-${rule.id}").click()

    assertThat(page.locator("[data-testid=toast][data-level=success]")).containsText("Disabled rule School events")
    assertThat(page.testId("enabled-rule-${rule.id}")).not().isChecked()
    page.testId("enabled-rule-${rule.id}").click()
    assertThat(page.testId("enabled-rule-${rule.id}")).isChecked()
  }
}
