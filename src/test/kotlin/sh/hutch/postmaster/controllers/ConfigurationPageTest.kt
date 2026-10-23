package sh.hutch.postmaster.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.testsupport.AdminProfile
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.testId

// REQ Configuration 1-4; CLAR Q17 (encrypted values masked), D9
@QuarkusTest
@TestProfile(AdminProfile::class)
class ConfigurationPageTest {
  @Inject
  lateinit var settings: SettingsService

  @AfterEach
  fun restore() {
    settings.update("llm.model", "qwen3.8-9b-100k")
    settings.update("processing.frequency", "1h")
    settings.clear("imap.password")
  }

  @Test
  fun `the page lists every setting in order with its label`() {
    // REQ Configuration 1
    val page = getHtml("/configuration")

    val rows = page.select("[data-testid=settings-table] tr[data-testid^=setting-row-]")
    assertEquals(settings.list().map { it.key }, rows.map { it.attr("data-testid").removePrefix("setting-row-") })
    assertTrue(page.testId("setting-row-imap.host").text().contains("Dovecot server address"))
  }

  @Test
  fun `a value is edited in place and saved`() {
    // REQ Configuration 2
    val edit = parseHtml(given().get("/configuration/llm.model/edit").then().statusCode(200).extract().asString())
    val input = edit.testId("setting-input-llm.model")
    assertEquals("text", input.attr("type"))
    assertEquals("qwen3.8-9b-100k", input.attr("value"))

    val saved = put("llm.model", "other-model")

    assertEquals(200, saved.statusCode)
    assertEquals("other-model", parseHtml(saved.asString()).testId("setting-value-llm.model").text())
    assertEquals(listOf("Saved LLM model"), toasts(saved))
    assertEquals("other-model", settings.getString("llm.model"))
  }

  @Test
  fun `an invalid value is refused and explained`() {
    // REQ Configuration 4 (durations must be ISO 8601)
    val refused = put("processing.frequency", "every hour")

    assertEquals(422, refused.statusCode)
    val fragment = parseHtml(refused.asString())
    assertEquals("true", fragment.testId("setting-input-processing.frequency").attr("aria-invalid"))
    assertEquals("every hour", fragment.testId("setting-input-processing.frequency").attr("value"))
    assertTrue(fragment.select("[data-testid=setting-error-processing.frequency]").text().isNotBlank())
    assertEquals("1h", settings.getString("processing.frequency"))
  }

  @Test
  fun `durations can be given in full or simplified ISO 8601`() {
    // REQ Configuration 4
    for (value in listOf("PT2H", "2H", "2h")) {
      assertEquals(200, put("processing.frequency", value).statusCode, value)
    }
  }

  @Test
  fun `an encrypted value is shown masked, edited in an empty password field and stored encrypted`() {
    // REQ Configuration 3; CLAR Q17
    assertEquals(200, put("imap.password", "s3cret").statusCode)

    val display = getHtml("/configuration").testId("setting-value-imap.password")
    assertEquals(SettingsService.MASK, display.text())
    assertFalse(getHtml("/configuration").html().contains("s3cret"))
    val input = parseHtml(given().get("/configuration/imap.password/edit").asString())
        .testId("setting-input-imap.password")
    assertEquals("password", input.attr("type"))
    assertEquals("", input.attr("value"))
    assertEquals("s3cret", settings.getString("imap.password"))
  }

  @Test
  fun `saving an empty encrypted field keeps the value and raises no toast`() {
    // CLAR Q17
    put("imap.password", "kept")

    val unchanged = put("imap.password", "")

    assertEquals(200, unchanged.statusCode)
    assertEquals(emptyList<String>(), toasts(unchanged))
    assertEquals("kept", settings.getString("imap.password"))
  }

  @Test
  fun `an encrypted setting can be cleared, but other settings can't`() {
    put("imap.password", "clear me")

    val cleared = given().delete("/configuration/imap.password")
    assertEquals(200, cleared.statusCode)
    assertNull(settings.getString("imap.password"))
    assertEquals(409, given().delete("/configuration/imap.port").statusCode)
  }

  @Test
  fun `an unknown setting is not found`() {
    assertEquals(404, given().get("/configuration/no.such.key/edit").statusCode)
  }

  private fun put(key: String, value: String): Response =
      given().header("HX-Request", "true").formParam("value", value).put("/configuration/$key")

  private fun toasts(response: Response): List<String> {
    val header = response.header("HX-Trigger") ?: return emptyList()
    val toasts = ObjectMapper().readTree(header)["pm-toast"]?.get("toasts") ?: return emptyList()
    return toasts.map { it["message"].asText() }
  }
}
