package sh.hutch.postmaster.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.processing.PipelineTest
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.update
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The status endpoint (CLAR R7): structured JSON on whether the application
 * and its dependencies are up, its version and uptime, and what processing
 * is doing. OK and DEGRADED answer 200; DOWN answers 503. A client asking
 * for application/health+json gets the IETF draft's format.
 */
@QuarkusTest
class HealthTest : PipelineTest() {
  private val json = ObjectMapper()
  private lateinit var todo: Path

  private val allChecks = listOf(
      "database", "dovecot", "llm", "smtp", "settings", "processing", "khal", "chromium", "curl", "attachments", "todo", "todo_list", "disk"
  )

  @BeforeEach
  fun setUpHealth() {
    todo = Files.createTempDirectory("postmaster-todo")
    Files.writeString(todo.resolve("index.md"), "# To-do\n<!-- todo-items:end -->\n")
    settings.update("todo.path", todo.toString())
    settings.update("todo.postmaster_url", "http://tigerbear:8080/mail")
    // Other tests' items were written to to-do lists of their own, not this one
    dataSource.update("UPDATE Email_action_items SET todo_name = NULL")
    settings.update("health.pending_limit", "25")
    settings.update("health.min_free_mb", "1")
  }

  private fun llmUp() {
    wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(okJson("""{"object": "list", "data": [{"id": "qwen3.8-9b-100k"}]}""")))
  }

  private fun fetch(path: String = "/health", accept: String? = null): Pair<Response, JsonNode> {
    val response = given().apply { accept?.let { header("Accept", it) } }.get(path)
    return response to json.readTree(response.asString())
  }

  @Test
  fun `with everything up the status is OK`() {
    llmUp()

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType.startsWith("application/json"), response.contentType)
    assertTrue(response.header("Cache-Control").contains("no-store"))
    assertEquals("OK", body["status"].asText(), body["checks"].toString())
    assertEquals(allChecks, body["checks"].fieldNames().asSequence().toList())
    for (name in allChecks) {
      assertEquals("UP", body["checks"][name]["status"].asText(), "$name: ${body["checks"][name]}")
      assertTrue(body["checks"][name]["responseTimeMs"].asLong() >= 0)
      assertEquals(name == "database" || name == "dovecot", body["checks"][name]["critical"].asBoolean(), name)
    }
    assertEquals("Models: qwen3.8-9b-100k", body["checks"]["llm"]["output"].asText())
    assertEquals("127.0.0.1:${greenMail.imap.port}", body["checks"]["dovecot"]["target"].asText())
    assertFalse(body["checks"]["database"]["target"].asText().contains(";"), "no JDBC options")
    assertEquals("Chromium 140.0.0.0 (fake)", body["checks"]["chromium"]["output"].asText())
    assertTrue(body["checks"]["curl"]["output"].asText().startsWith("curl "), body["checks"]["curl"].toString())
    assertTrue(body["checks"]["khal"]["output"].asText().startsWith("Calendars: "), body["checks"]["khal"].toString())
    assertEquals(todo.toString(), body["checks"]["todo"]["target"].asText())
    assertTrue(body["checks"]["disk"]["output"].asText().contains(" free"), body["checks"]["disk"].toString())
    assertEquals("Scheduled processing is off", body["checks"]["processing"]["output"].asText())
  }

  @Test
  fun `the application's version, build and uptime are given`() {
    llmUp()

    val application = fetch().second["application"]

    assertEquals("postmaster", application["name"].asText())
    assertTrue(Regex("v\\d+").matches(application["version"].asText()), application["version"].asText())
    assertTrue(Regex("[0-9a-f]{12}(-dirty)?").matches(application["commit"].asText()), application["commit"].asText())
    assertTrue(application["builtAt"].isNull, "test builds have no build time")
    val started = Instant.parse(application["startedAt"].asText())
    assertTrue(started.isBefore(Instant.now()))
    assertTrue(application["uptime"].asText().startsWith("PT"))
    assertTrue(application["uptimeSeconds"].asLong() >= 0)
  }

  @Test
  fun `status is the same as health`() {
    llmUp()

    val (response, body) = fetch("/status")

    assertEquals(200, response.statusCode)
    assertEquals("OK", body["status"].asText())
    assertEquals(setOf("status", "time", "application", "processing", "checks"), body.fieldNames().asSequence().toSet())
  }

  @Test
  fun `without the LLM the status is DEGRADED`() {
    wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(503)))

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertEquals("DEGRADED", body["status"].asText())
    assertEquals("DOWN", body["checks"]["llm"]["status"].asText())
    assertTrue(body["checks"]["llm"]["output"].asText().contains("503"))
  }

  @Test
  fun `without SMTP the status is DEGRADED`() {
    llmUp()
    settings.update("smtp.port", "1")

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertEquals("DEGRADED", body["status"].asText())
    assertEquals("DOWN", body["checks"]["smtp"]["status"].asText())
    assertEquals("UP", body["checks"]["dovecot"]["status"].asText())
  }

  @Test
  fun `without Dovecot the status is DOWN, with 503`() {
    llmUp()
    settings.update("imap.port", "1")

    val (response, body) = fetch()

    assertEquals(503, response.statusCode)
    assertEquals("DOWN", body["status"].asText())
    assertEquals("DOWN", body["checks"]["dovecot"]["status"].asText())
    assertTrue(body["checks"]["dovecot"]["output"].asText().contains("could not be reached"), body["checks"]["dovecot"].toString())
  }

  @Test
  fun `a wrong Dovecot password is DOWN too`() {
    llmUp()
    settings.update("imap.password", "wrong")

    val body = fetch().second

    assertEquals("DOWN", body["status"].asText())
    assertTrue(body["checks"]["dovecot"]["output"].asText().contains("rejected"), body["checks"]["dovecot"].toString())
  }

  @Test
  fun `a dependency that doesn't answer is DOWN after the timeout`() {
    wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(okJson("""{"data": []}""").withFixedDelay(8000)))
    val started = System.nanoTime()

    val body = fetch().second

    assertTrue((System.nanoTime() - started) / 1_000_000 < 7500, "the report doesn't wait for it")
    assertEquals("DOWN", body["checks"]["llm"]["status"].asText())
    assertEquals("DEGRADED", body["status"].asText())
  }

  @Test
  fun `processing is reported, including a run in progress`() {
    llmUp()
    llm(SUMMARISE, """{"summary": "slow"}""", delayMs = 3000)
    inbox(message("<health-slow@mail.example>", "Slow"))
    val run = CompletableFuture.supplyAsync { processing.runNow(RunTrigger.MANUAL) }
    Thread.sleep(800)

    val during = fetch().second["processing"]

    assertTrue(during["inProgress"].asBoolean())
    assertEquals("running", during["currentRun"]["status"].asText())
    assertEquals("manual", during["currentRun"]["trigger"].asText())
    run.get(30, TimeUnit.SECONDS)

    val after = fetch().second["processing"]
    assertFalse(after["inProgress"].asBoolean())
    assertTrue(after["currentRun"].isNull)
    assertEquals("succeeded", after["lastRun"]["status"].asText())
    assertFalse(after["lastSuccessAt"].isNull)
    assertFalse(after["scheduled"].asBoolean(), "tests run without the scheduler")
    assertTrue(after["nextRunAt"].isNull)
    assertTrue(after["pendingEmails"].asLong() >= 0)
    assertTrue(after["failedEmailsLastDay"].asLong() >= 0)
  }

  @Test
  fun `khal or Chromium failing makes the status DEGRADED`() {
    llmUp()
    FakeTools.failKhal("khal: config error")
    FakeTools.failChromium("chromium: crashed")

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertEquals("DEGRADED", body["status"].asText())
    assertEquals("DOWN", body["checks"]["khal"]["status"].asText())
    assertTrue(body["checks"]["khal"]["output"].asText().endsWith("exit code 1: khal: config error"), body["checks"]["khal"].toString())
    assertEquals("DOWN", body["checks"]["chromium"]["status"].asText())
    assertEquals("UP", body["checks"]["curl"]["status"].asText())
  }

  @Test
  fun `a to-do index without its end line is DOWN`() {
    llmUp()
    Files.writeString(todo.resolve("index.md"), "# To-do\n- [ ] [[Pay]]\n")

    val body = fetch().second

    assertEquals("DEGRADED", body["status"].asText())
    assertEquals("UP", body["checks"]["todo"]["status"].asText(), "the folder is fine")
    assertEquals("DOWN", body["checks"]["todo_list"]["status"].asText())
    assertTrue(body["checks"]["todo_list"]["output"].asText().contains("<!-- todo-items:end -->"))
  }

  @Test
  fun `the to-do list check counts Postmaster's items and warns about missing files`() {
    // CLAR R9
    llmUp()
    inbox(message("<health-todo-${System.nanoTime()}@mail.example>", "Health to-do"))
    processing.runNow(RunTrigger.MANUAL)
    val email = dataSource.rows("SELECT id FROM Emails ORDER BY id DESC").first()["id"]
    dataSource.update("DELETE FROM Email_action_items WHERE todo_name IS NOT NULL")
    for ((name, done) in listOf("Health open" to false, "Health done" to true, "Health gone" to false)) {
      dataSource.update(
          "INSERT INTO Email_action_items (email_id, position, description, completed, in_progress, todo_name, created_at, last_updated_at) " +
              "VALUES (?, 9, ?, ?, FALSE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
          email, name, done, name
      )
    }
    Files.writeString(todo.resolve("Health open.md"), "---\ncompleted: false\n---\n")
    Files.writeString(todo.resolve("Health done.md"), "---\ncompleted: true\n---\n")

    val check = fetch().second["checks"]["todo_list"]

    assertEquals("WARN", check["status"].asText())
    assertEquals(todo.resolve("index.md").toString(), check["target"].asText())
    val output = check["output"].asText()
    assertTrue(output.startsWith("3 items from Postmaster: 2 open, 0 in progress, 1 done"), output)
    assertTrue(output.endsWith("the files of 1 are missing: Health gone"), output)

    dataSource.update("DELETE FROM Email_action_items WHERE todo_name IS NOT NULL")
    settings.update("todo.postmaster_url", "")
    val unlinked = fetch().second["checks"]["todo_list"]
    assertEquals("WARN", unlinked["status"].asText())
    assertTrue(unlinked["output"].asText().endsWith("todo.postmaster_url isn't set, so items don't link back to their email"), unlinked.toString())

    Files.delete(todo.resolve("index.md"))
    val missing = fetch().second["checks"]["todo_list"]
    assertEquals("WARN", missing["status"].asText())
    assertTrue(missing["output"].asText().contains("doesn't exist"), missing.toString())
  }

  @Test
  fun `a folder that doesn't exist yet is fine if it can be created`() {
    llmUp()
    settings.update("todo.path", todo.resolve("later/todo").toString())
    settings.update("attachments.path", "/nonexistent-root-folder/attachments")

    val checks = fetch().second["checks"]

    assertEquals("UP", checks["todo"]["status"].asText())
    assertTrue(checks["todo"]["output"].asText().contains("will be created"))
    assertEquals("DOWN", checks["attachments"]["status"].asText())
    assertTrue(checks["attachments"]["output"].asText().contains("can't be created"), checks["attachments"].toString())
  }

  @Test
  fun `little free disk space warns`() {
    llmUp()
    settings.update("health.min_free_mb", Int.MAX_VALUE.toString())

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertEquals("DEGRADED", body["status"].asText())
    assertEquals("WARN", body["checks"]["disk"]["status"].asText())
    assertTrue(body["checks"]["disk"]["output"].asText().startsWith("Less than 2097152.0 GiB free: "), body["checks"]["disk"].toString())
  }

  @Test
  fun `emails piling up warns`() {
    llmUp()
    inbox(message("<health-pending-1@mail.example>", "One"), message("<health-pending-2@mail.example>", "Two"))
    processing.runNow(RunTrigger.MANUAL)
    // As if the LLM had been down for both
    dataSource.update("UPDATE Emails SET status = 'pending' WHERE message_id LIKE 'health-pending-%'")
    settings.update("health.pending_limit", "1")
    try {
      val body = fetch().second

      assertEquals("DEGRADED", body["status"].asText())
      assertEquals("WARN", body["checks"]["processing"]["status"].asText())
      assertTrue(body["checks"]["processing"]["output"].asText().contains("emails are waiting to be processed (more than 1)"))
    } finally {
      dataSource.update("UPDATE Emails SET deleted = TRUE WHERE message_id LIKE 'health-pending-%'")
    }
  }

  @Test
  fun `a password the encryption key can't decrypt is reported`() {
    llmUp()
    val original = dataSource.rows("SELECT setting_value FROM Settings WHERE setting_key = 'llm.api_key'").single()["setting_value"]
    dataSource.update("UPDATE Settings SET setting_value = 'enc:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' WHERE setting_key = 'llm.api_key'")
    try {
      val body = fetch().second

      assertEquals("DEGRADED", body["status"].asText())
      assertEquals("DOWN", body["checks"]["settings"]["status"].asText())
      assertTrue(body["checks"]["settings"]["output"].asText().endsWith(": llm.api_key"), body["checks"]["settings"].toString())
    } finally {
      dataSource.update("UPDATE Settings SET setting_value = ? WHERE setting_key = 'llm.api_key'", original)
    }
  }

  @Test
  fun `application health+json gives the IETF draft's format`() {
    llmUp()
    settings.update("smtp.port", "1")

    val (response, body) = fetch(accept = "application/health+json")

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType.startsWith("application/health+json"), response.contentType)
    assertTrue(response.header("Cache-Control").contains("no-store"))
    assertEquals("warn", body["status"].asText())
    assertEquals("postmaster", body["serviceId"].asText())
    assertTrue(Regex("v\\d+").matches(body["version"].asText()))
    assertTrue(body["output"].asText().startsWith("smtp: "), body["output"].asText())
    assertEquals(listOf("uptime") + allChecks.map { "$it:responseTime" }, body["checks"].fieldNames().asSequence().toList())
    val smtp = body["checks"]["smtp:responseTime"][0]
    assertEquals("fail", smtp["status"].asText())
    assertEquals("mail", smtp["componentType"].asText())
    assertEquals("127.0.0.1:1", smtp["componentId"].asText())
    assertEquals("ms", smtp["observedUnit"].asText())
    assertTrue(smtp["observedValue"].isNumber)
    val database = body["checks"]["database:responseTime"][0]
    assertEquals("pass", database["status"].asText())
    assertFalse(database.has("output"), "fields without a value are left out")
    assertEquals("s", body["checks"]["uptime"][0]["observedUnit"].asText())
  }

  @Test
  fun `the draft's format is only given when it's preferred`() {
    llmUp()
    settings.update("imap.port", "1")

    val (plain, plainBody) = fetch("/status", accept = "application/json, application/health+json;q=0.5")
    val (wildcard, _) = fetch(accept = "*/*")
    val (draft, draftBody) = fetch("/status", accept = "application/health+json")

    assertTrue(plain.contentType.startsWith("application/json"))
    assertEquals("DOWN", plainBody["status"].asText())
    assertTrue(wildcard.contentType.startsWith("application/json"))
    assertEquals(503, draft.statusCode)
    assertEquals("fail", draftBody["status"].asText())
  }
}
