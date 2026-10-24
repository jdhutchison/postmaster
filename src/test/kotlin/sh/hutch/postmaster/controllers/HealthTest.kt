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
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.processing.PipelineTest
import sh.hutch.postmaster.testsupport.TestMessages.message
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The status endpoint (CLAR R7): structured JSON on whether the application
 * and its dependencies are up, its version and uptime, and what processing
 * is doing. OK and DEGRADED answer 200; DOWN answers 503.
 */
@QuarkusTest
class HealthTest : PipelineTest() {
  private val json = ObjectMapper()

  private fun llmUp() {
    wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(okJson("""{"object": "list", "data": [{"id": "qwen3.8-9b-100k"}]}""")))
  }

  private fun fetch(path: String = "/health"): Pair<Response, JsonNode> {
    val response = given().get(path)
    return response to json.readTree(response.asString())
  }

  @Test
  fun `with everything up the status is OK`() {
    llmUp()

    val (response, body) = fetch()

    assertEquals(200, response.statusCode)
    assertTrue(response.contentType.startsWith("application/json"), response.contentType)
    assertTrue(response.header("Cache-Control").contains("no-store"))
    assertEquals("OK", body["status"].asText())
    for (name in listOf("database", "dovecot", "llm", "smtp")) {
      assertEquals("UP", body["checks"][name]["status"].asText(), "$name: ${body["checks"][name]}")
      assertTrue(body["checks"][name]["responseTimeMs"].asLong() >= 0)
    }
    assertEquals(listOf(true, true, false, false), listOf("database", "dovecot", "llm", "smtp").map { body["checks"][it]["critical"].asBoolean() })
    assertEquals("Models: qwen3.8-9b-100k", body["checks"]["llm"]["output"].asText())
    assertEquals("127.0.0.1:${greenMail.imap.port}", body["checks"]["dovecot"]["target"].asText())
    assertFalse(body["checks"]["database"]["target"].asText().contains(";"), "no JDBC options")
  }

  @Test
  fun `the application's version, build and uptime are given`() {
    llmUp()

    val application = fetch().second["application"]

    assertEquals("postmaster", application["name"].asText())
    assertTrue(Regex("v\\d+").matches(application["version"].asText()), application["version"].asText())
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
}
