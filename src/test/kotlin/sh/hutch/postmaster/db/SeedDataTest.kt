package sh.hutch.postmaster.db

import com.fasterxml.jackson.databind.ObjectMapper
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.rows

/**
 * The data the migrations seed (PLAN 1.2). Runs under its own profile so the
 * database holds nothing but the seeds.
 */
@QuarkusTest
@TestProfile(SeedDataTest.SeedsOnly::class)
class SeedDataTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  // Types

  @Test
  fun `the initial types are exactly those in the requirements`() {
    // REQ Types 9 (trailing spaces in the requirements' table trimmed)
    val types = dataSource.rows("SELECT name, description FROM Types WHERE deleted = FALSE")
        .associate { it["name"] as String to it["description"] as String? }

    val expected = mapOf(
        "Bill" to "A utility bill",
        "Primary school" to "Message from Brunswick South",
        "High school" to "Message from Princes Hill SC",
        "Statement notification" to "Statement available for card or bank",
        "Legal" to null,
        "Caloundra West" to "Message about property",
        "Ebay" to "Ebay business",
        "Payslip" to "Payslips",
        "Showfit" to "Message about Jeremy's dancing"
    )
    assertEquals(expected.keys, types.keys)
    for ((name, description) in expected) {
      if (description == null) {
        assertTrue(types[name].isNullOrBlank(), "$name has no description")
      } else {
        assertEquals(description, types[name], "$name description")
      }
    }
  }

  @Test
  fun `every initial type has an RGB hex colour`() {
    // REQ Types 8 and Types schema (colour); CLAR D2
    for (type in dataSource.rows("SELECT name, colour FROM Types")) {
      assertTrue(HEX_COLOUR.matches(type["colour"] as String), "${type["name"]} colour ${type["colour"]}")
    }
  }

  @Test
  fun `Bill is the only system type`() {
    // CLAR Q16: Bill is a system type with a fixed key. The other types can be
    // deleted (REQ Types 4, 5), so they must not be system types.
    val systemTypes = dataSource.rows("SELECT name, system_key FROM Types WHERE system_key IS NOT NULL")

    assertEquals(listOf("Bill"), systemTypes.map { it["name"] })
    assertEquals("bill", systemTypes.single()["system_key"])
  }

  // Prompts

  @Test
  fun `the initial prompts are the requirements' prompts plus identify_events`() {
    // REQ Prompts 5 and "Prompts for using with the LLM"; CLAR Q15 (fixed keys
    // and an identify_events prompt, referred to by REQ create_events)
    val prompts = dataSource.rows("SELECT prompt_key, name FROM Prompts WHERE deleted = FALSE")
        .associate { it["prompt_key"] as String? to it["name"] as String }

    assertEquals(
        mapOf(
            "summarise" to "Summarising the email",
            "classify" to "Classifying and summarising email",
            "select_calendar" to "Selecting a calendar",
            "summarise_page" to "Summarising contents of fetched page",
            "bill_details" to "Checking for the bill details",
            "identify_events" to "Identifying events"
        ),
        prompts
    )
  }

  @Test
  fun `the Summarise prompt is the revised prompt in R1`() {
    // CLAR Q13 revision and R1: the Summarise prompt also identifies required
    // action, and D19 adds the email placeholders and fixes the JSON
    val expected = SeedDataTest::class.java.getResource("/expected/prompts/summarise.txt")!!.readText().trimEnd()

    assertEquals(expected, prompt("summarise").trimEnd())
  }

  @Test
  fun `prompts use only the defined placeholders`() {
    // REQ Editing or creating a prompt 4 (the seven placeholders) plus the
    // types table (CLAR D19); placeholder syntax {{name}} (CLAR Q19)
    for ((key, contents) in prompts()) {
      val used = PLACEHOLDER.findAll(contents).map { it.groupValues[1] }.toSet()
      assertTrue(PLACEHOLDERS.containsAll(used), "$key uses only defined placeholders: $used")
    }
  }

  @Test
  fun `no prompt keeps a placeholder description from the requirements`() {
    // CLAR D19: the requirements' "<sender placeholder>" style markers are replaced by real placeholders
    for ((key, contents) in prompts()) {
      assertFalse(contents.contains("placeholder>"), "$key still has a '<… placeholder>' marker")
    }
  }

  @Test
  fun `each prompt includes the email or page content it works on`() {
    // REQ "Prompts for using with the LLM" (the placeholders each prompt shows)
    // and CLAR D19 (Summarise gets the email; Selecting a calendar gets
    // enabled calendars and events with ids)
    val expected = mapOf(
        "summarise" to setOf("sender", "subject", "text"),
        "classify" to setOf("types", "sender", "subject", "text"),
        "select_calendar" to setOf("calendars", "events"),
        "summarise_page" to setOf("page"),
        "bill_details" to setOf("sender", "subject", "html"),
        "identify_events" to setOf("sender", "subject", "text")
    )
    for ((key, placeholders) in expected) {
      val used = PLACEHOLDER.findAll(prompt(key)).map { it.groupValues[1] }.toSet()
      assertTrue(used.containsAll(placeholders), "$key uses $placeholders; uses $used")
    }
  }

  @Test
  fun `JSON examples in prompts are valid JSON`() {
    // CLAR D19: the requirements' Summarise example was missing commas, and
    // small models copy mistakes. The <…> value descriptions are template
    // text, so they are replaced with sample values before parsing.
    for (key in listOf("summarise", "bill_details", "identify_events")) {
      val example = jsonExample(prompt(key))
          .replace("<true|false>", "true")
          .replace("<list of strings specifying errors>", "\"error\"")
      val parsed = runCatching { ObjectMapper().readTree(example) }
      assertTrue(parsed.isSuccess, "$key example parses: ${parsed.exceptionOrNull()?.message}\n$example")
    }
  }

  @Test
  fun `the classify prompt keeps the requirements' instructions`() {
    // REQ "Classifying and summarising email"
    val contents = prompt("classify")

    assertTrue(contents.contains("Select one of the following email types:"))
    assertTrue(
        contents.contains(
            "Answer with the name of a valid type or with the message \"NO CLASSIFICATION\" only. No other text like " +
                "reasoning - only the name of the type or \"NO CLASSIFICATION\"."
        )
    )
  }

  @Test
  fun `the calendar selection prompt keeps the requirements' instructions`() {
    // REQ "Selecting a calendar"
    val contents = prompt("select_calendar")

    assertTrue(
        contents.contains(
            "For each event in the following JSON list select one of these calendars that best matches the details " +
                "of the event."
        )
    )
    assertTrue(contents.contains("If no calendar seems to match then do not return a recommendation."))
    assertTrue(
        contents.contains(
            "Return recommendations as a JSON map with the event ids as the keys and the recommended calendar name " +
                "as the value."
        )
    )
  }

  @Test
  fun `the page summary prompt keeps the requirements' instructions`() {
    // REQ "Summarising contents of fetched page"
    assertTrue(
        prompt("summarise_page").contains(
            "Summarise the contents of this webpage from its HTML. Most navigation elements should be removed but " +
                "ignore any remaining."
        )
    )
  }

  @Test
  fun `the bill details prompt asks for every bill attribute`() {
    // REQ "Checking for the bill details"
    val contents = prompt("bill_details")

    for (line in listOf(
        "- The organisation to pay",
        "- The date payment is due",
        "- The amount that is due",
        "- The link to do the payment",
        "- The URL to download the bill (this may or may not be present)"
    )) {
      assertTrue(contents.contains(line), "bill_details asks for '$line'")
    }
    val example = ObjectMapper().readTree(jsonExample(contents))
    assertEquals(
        setOf("organisation", "due_date", "amount_due", "payment_link", "bill_url"),
        example.fieldNames().asSequence().toSet()
    )
    assertTrue(contents.trimEnd().endsWith("Do not guess any details."))
  }

  @Test
  fun `the identify_events prompt returns events in the same shape as Summarise`() {
    // CLAR Q15 (identify_events, used by REQ create_events) and REQ
    // "Summarising the email" event fields
    val example = ObjectMapper().readTree(
        jsonExample(prompt("identify_events")).replace("<list of strings specifying errors>", "\"error\"")
    )

    val event = example["events"][0]
    assertEquals(setOf("title", "when", "location", "notes"), event.fieldNames().asSequence().toSet())
    assertTrue(prompt("identify_events").contains("Do not guess any details."))
  }

  // Settings

  @Test
  fun `the configuration settings in the requirements exist with the right types`() {
    // REQ Configuration (database settings list) and Fetching emails (password AES encrypted)
    assertSetting("imap.host", "text")
    assertSetting("imap.username", "text")
    assertSetting("imap.password", "encrypted")
    assertSetting("llm.url", "url")
    assertSetting("llm.api_key", "encrypted")
    assertSetting("attachments.path", "path")
    assertSetting("attachments.extensions", "list")
  }

  @Test
  fun `the settings added by the clarifications exist with the right types`() {
    // CLAR D9 (IMAP, LLM, timezone, durations, fetching, link text), Q7 (SMTP), Q14 (lookback)
    assertSetting("imap.port", "int")
    assertSetting("imap.security", "choice")
    assertSetting("imap.inbox_folder", "text")
    assertSetting("imap.trash_folder", "text")
    assertSetting("smtp.host", "text")
    assertSetting("smtp.port", "int")
    assertSetting("smtp.username", "text")
    assertSetting("smtp.password", "encrypted")
    assertSetting("smtp.from_address", "text")
    assertSetting("llm.model", "text")
    assertSetting("llm.timeout", "duration")
    assertSetting("llm.max_input_chars", "int")
    assertSetting("processing.frequency", "duration")
    assertSetting("processing.lookback", "duration")
    assertSetting("processing.timezone", "timezone")
    assertSetting("events.default_duration", "duration")
    assertSetting("fetch.user_agent", "text")
    assertSetting("fetch.timeout", "duration")
    assertSetting("bills.pay_link_text", "list")
    assertSetting("bills.bill_link_text", "list")
  }

  @Test
  fun `defaults match the requirements and clarifications`() {
    assertEquals("1h", value("processing.frequency"), "REQ Fetching emails: default frequency 1h")
    assertEquals("7d", value("processing.lookback"), "CLAR Q14: lookback default 7 days")
    assertEquals("Australia/Melbourne", value("processing.timezone"), "CLAR D8: timezone default")
    assertEquals("1h", value("events.default_duration"), "CLAR D8: default event duration 1h")
    assertEquals("Trash", value("imap.trash_folder"), "CLAR Q6: trash folder default Trash")
    assertEquals("mail-opensmtp", value("smtp.host"), "CLAR Q7: SMTP defaults to mail-opensmtp")
    assertEquals("25", value("smtp.port"), "CLAR Q7: SMTP port 25")
    assertEquals("http://kodiakbear:9931/v1", value("llm.url"), "CLAR Q4: llama.cpp on kodiakbear")
  }

  @Test
  fun `the fetch user agent simulates Chrome on Windows`() {
    // REQ Technical choices: "a headless browser that simulates Chrome running on Windows in the user agent"
    val agent = value("fetch.user_agent")!!

    assertTrue(agent.contains("Windows NT"), agent)
    assertTrue(agent.contains("Chrome/"), agent)
  }

  @Test
  fun `the pay link text includes Pay now`() {
    // REQ process bill: 'The link to pay will have the anchor text "Pay now" or something similar'
    val texts = value("bills.pay_link_text")!!.split(", ")

    assertTrue("Pay now" in texts, "pay link texts: $texts")
  }

  @Test
  fun `there is no bill reminder calendar setting`() {
    // CLAR Q16: the reminder calendar is set on each process_bill action instead
    val keys = dataSource.rows("SELECT setting_key FROM Settings").map { it["setting_key"] as String }

    assertTrue(keys.none { it.contains("reminder") }, "settings: $keys")
  }

  @Test
  fun `every setting has a label and a description`() {
    // REQ Settings schema (label); CLAR D9 (description for help text)
    for (setting in dataSource.rows("SELECT setting_key, label, description FROM Settings")) {
      assertFalse((setting["label"] as String?).isNullOrBlank(), "${setting["setting_key"]} label")
      assertFalse((setting["description"] as String?).isNullOrBlank(), "${setting["setting_key"]} description")
    }
  }

  @Test
  fun `encrypted settings are either unset or stored with the enc prefix`() {
    // REQ Configuration: values stored encrypted are prefixed with "enc:"
    for (setting in dataSource.rows("SELECT setting_key, setting_value FROM Settings WHERE value_type = 'encrypted'")) {
      val value = setting["setting_value"] as String?
      assertTrue(value == null || value.startsWith("enc:"), "${setting["setting_key"]} = $value")
    }
  }

  @Test
  fun `seeded duration settings hold full or simplified ISO 8601 durations`() {
    // REQ Configuration 4
    for (setting in dataSource.rows("SELECT setting_key, setting_value FROM Settings WHERE value_type = 'duration'")) {
      val value = setting["setting_value"] as String?
      assertNotNull(value, "${setting["setting_key"]} has a default")
      assertTrue(DURATION.matches(value!!), "${setting["setting_key"]} = $value")
    }
  }

  @Test
  fun `nothing is seeded as deleted`() {
    for (table in listOf("Types", "Prompts")) {
      assertEquals(0L, (dataSource.row("SELECT COUNT(*) AS n FROM $table WHERE deleted = TRUE")["n"] as Number).toLong())
    }
  }

  private fun prompts(): Map<String, String> =
      dataSource.rows("SELECT prompt_key, contents FROM Prompts")
          .associate { (it["prompt_key"] as String) to (it["contents"] as String) }

  private fun prompt(key: String): String =
      dataSource.row("SELECT contents FROM Prompts WHERE prompt_key = ?", key)["contents"] as String

  private fun value(key: String): String? =
      dataSource.row("SELECT setting_value FROM Settings WHERE setting_key = ?", key)["setting_value"] as String?

  private fun assertSetting(key: String, type: String) {
    val rows = dataSource.rows("SELECT value_type FROM Settings WHERE setting_key = ?", key)
    assertEquals(1, rows.size, "setting $key exists")
    assertEquals(type, rows.single()["value_type"], "setting $key type")
  }

  /** The JSON example in a prompt: from a line that is just "{" to the next line that is just "}" */
  private fun jsonExample(contents: String): String {
    val lines = contents.lines()
    val start = lines.indexOfFirst { it == "{" }
    val end = lines.withIndex().indexOfFirst { (index, line) -> index > start && line == "}" }
    check(start >= 0 && end > start) { "No JSON example found in:\n$contents" }
    return lines.subList(start, end + 1).joinToString("\n")
  }

  /** A distinct profile, so this class gets an application and database of its own */
  class SeedsOnly : QuarkusTestProfile

  companion object {
    private val HEX_COLOUR = Regex("^#[0-9a-fA-F]{6}$")
    private val PLACEHOLDER = Regex("\\{\\{\\s*([a-z_]+)\\s*}}")
    private val PLACEHOLDERS = setOf("calendars", "events", "subject", "sender", "text", "html", "page", "types")
    private val DURATION = Regex(
        "^(P(\\d+W)?(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?|(\\d+[wdhms])+)$",
        RegexOption.IGNORE_CASE
    )
  }
}
