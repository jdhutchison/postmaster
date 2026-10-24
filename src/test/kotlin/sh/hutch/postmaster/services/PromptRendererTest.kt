package sh.hutch.postmaster.services

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.PromptContext
import sh.hutch.postmaster.models.PromptEvent
import sh.hutch.postmaster.repositories.PromptRepository
import sh.hutch.postmaster.testsupport.IntegrationProfile

/**
 * Filling in prompt placeholders (REQ Editing or creating a prompt 4 and the
 * prompts' placeholders; CLAR Q19, D19, R1, S10 (input cut to the maximum
 * characters)), on the known data set.
 */
@QuarkusTest
@TestProfile(IntegrationProfile::class)
class PromptRendererTest {
  @Inject
  lateinit var renderer: PromptRenderer

  @Inject
  lateinit var settings: SettingsService

  @Inject
  lateinit var prompts: PromptRepository

  private val context = PromptContext(
      subject = "Zoo excursion",
      sender = "office@brunswicksouth.example",
      text = "Grade 3 are going to the zoo.",
      html = "<p>Grade 3</p>",
      page = "<h1>Newsletter</h1>",
      events = listOf(PromptEvent(7, "Zoo excursion", "2026-11-05T09:00", "Melbourne Zoo", null))
  )

  @Test
  @TestTransaction
  fun `the email placeholders are filled and the JSON example is left intact`() {
    // CLAR R1, D19
    val rendered = renderer.render("summarise", context)

    assertTrue(rendered.contains("Sender: office@brunswicksouth.example"))
    assertTrue(rendered.contains("Subject: Zoo excursion"))
    assertTrue(rendered.contains("Contents: Grade 3 are going to the zoo."))
    assertFalse(rendered.contains("{{"), "no placeholders left")
    assertTrue(rendered.contains("\"response_required\": <true|false>"), "JSON braces untouched")
    assertTrue(rendered.contains("  \"events\": [\n    {\n"))
  }

  @Test
  @TestTransaction
  fun `the types placeholder lists the active types with their descriptions`() {
    val rendered = renderer.render("classify", context)

    assertTrue(rendered.contains("- Bill: A utility bill"), rendered)
    assertTrue(rendered.lines().any { it.trim() == "- Legal" }, "a type without a description")
    assertFalse(rendered.contains("Retired type"))
  }

  @Test
  @TestTransaction
  fun `the calendars placeholder lists only enabled calendars, and events are JSON with ids`() {
    // REQ "table of enabled calendars"; CLAR D19
    val rendered = renderer.render("select_calendar", context)

    assertTrue(rendered.contains("- Family-and-Kids: School events, excursions and family activities"))
    assertFalse(rendered.contains("Football"), "disabled calendar left out")
    assertFalse(rendered.contains("Retired calendar"))
    val json = rendered.substring(rendered.indexOf("[{"), rendered.indexOf("}]") + 2)
    val event = ObjectMapper().readTree(json)[0]
    assertEquals(7, event["id"].asInt())
    assertEquals("Zoo excursion", event["title"].asText())
    assertEquals("2026-11-05T09:00", event["when"].asText())
  }

  @Test
  @TestTransaction
  fun `page and HTML placeholders are filled`() {
    assertTrue(renderer.render("summarise_page", context).contains("<h1>Newsletter</h1>"))
    assertTrue(renderer.render("bill_details", context).contains("Contents: <p>Grade 3</p>"))
  }

  @Test
  @TestTransaction
  fun `long content is cut to the maximum input characters`() {
    // CLAR S10, D9 (llm.max_input_chars)
    settings.update("llm.max_input_chars", "10")

    val rendered = renderer.render("summarise", context.copy(text = "x".repeat(50)))

    assertTrue(rendered.contains("Contents: xxxxxxxxxx\n") || rendered.endsWith("Contents: xxxxxxxxxx"),
        rendered.takeLast(80))
    assertFalse(rendered.contains("x".repeat(11)))
  }

  @Test
  @TestTransaction
  fun `missing values are empty and unknown placeholders are left alone`() {
    prompts.findByKey("summarise_page")!!.contents = "Page: {{page}} Other: {{unknown}}"

    val rendered = renderer.render("summarise_page", PromptContext(null, null, null, null, null, emptyList()))

    assertEquals("Page:  Other: {{unknown}}", rendered)
  }

  @Test
  fun `an unknown prompt key is an error`() {
    assertThrows<IllegalStateException> { renderer.render("no_such_prompt", context) }
  }
}
