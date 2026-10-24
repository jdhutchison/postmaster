package sh.hutch.postmaster.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.testsupport.InjectWireMock
import sh.hutch.postmaster.testsupport.IntegrationProfile
import sh.hutch.postmaster.testsupport.WireMockTestResource

/**
 * Calls to the llama.cpp LLM (REQ "An LLM on llama.cpp"; CLAR Q4 (llama.cpp
 * directly, model is a setting), S10 (OpenAI-compatible API, JSON mode,
 * thinking turned off)), against WireMock.
 */
@QuarkusTest
@TestProfile(IntegrationProfile::class)
@WithTestResource(WireMockTestResource::class)
class LlmClientTest {
  @field:InjectWireMock
  lateinit var wireMock: WireMockServer

  @Inject
  lateinit var llm: LlmClient

  @Inject
  lateinit var settings: SettingsService

  private val mapper = ObjectMapper()

  @BeforeEach
  fun pointAtWireMock() {
    wireMock.resetAll()
    settings.update("llm.url", wireMock.baseUrl() + "/v1")
    settings.update("llm.model", "test-model")
    settings.update("llm.timeout", "10s")
    settings.clear("llm.api_key")
  }

  @AfterEach
  fun restore() {
    settings.update("llm.timeout", "2m")
  }

  private fun answer(content: String?, status: Int = 200) {
    val body = if (content == null) """{"choices":[{"message":{"role":"assistant"}}]}"""
    else mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content)))))
    wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
  }

  private fun lastRequest() = mapper.readTree(wireMock.allServeEvents.first().request.bodyAsString)

  @Test
  fun `a chat request names the model, sends the prompt and turns thinking off`() {
    answer("Bill")

    assertEquals("Bill", llm.chat("Classify this"))

    val request = lastRequest()
    assertEquals("test-model", request["model"].asText())
    assertEquals("user", request["messages"][0]["role"].asText())
    assertEquals("Classify this", request["messages"][0]["content"].asText())
    assertEquals(false, request["chat_template_kwargs"]["enable_thinking"].asBoolean())
    assertEquals(0, request["temperature"].asInt())
    assertEquals(false, request["stream"].asBoolean())
    assertFalse(request.has("response_format"), "plain chats don't ask for JSON")
  }

  @Test
  fun `a JSON request asks for a JSON object and returns it parsed`() {
    answer("""{"summary": "A bill", "events": []}""")

    val result = llm.chatJson("Summarise")

    assertEquals("A bill", result["summary"].asText())
    assertEquals("json_object", lastRequest()["response_format"]["type"].asText())
  }

  @Test
  fun `thinking and code fences are stripped from answers`() {
    answer("<think>Let me think</think>\n  Primary school  ")
    assertEquals("Primary school", llm.chat("Classify"))

    answer("```json\n{\"link\": \"https://x.example\"}\n```")
    assertEquals("https://x.example", llm.chatJson("Summarise")["link"].asText())
  }

  @Test
  fun `the API key is sent only when set`() {
    // REQ Configuration: "API key for the LLM instance (AES encrypted if set)"
    answer("ok")
    llm.chat("No key")
    assertFalse(wireMock.allServeEvents.first().request.containsHeader("Authorization"))

    settings.update("llm.api_key", "secret-key")
    wireMock.resetRequests()
    llm.chat("With key")
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer secret-key")))
  }

  @Test
  fun `a slow LLM times out`() {
    settings.update("llm.timeout", "1s")
    wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(okJson("{}").withFixedDelay(4000)))

    val started = System.nanoTime()
    val error = assertThrows<LlmException> { llm.chat("Slow") }

    assertTrue(error.message!!.contains("timed out"), error.message)
    assertTrue((System.nanoTime() - started) / 1e9 < 3.5, "gave up after the timeout")
  }

  @Test
  fun `HTTP errors, missing content and bad JSON are reported`() {
    answer("server broke", status = 500)
    assertTrue(assertThrows<LlmException> { llm.chat("x") }.message!!.contains("HTTP 500"))

    answer(null)
    assertTrue(assertThrows<LlmException> { llm.chat("x") }.message!!.contains("no content"))

    answer("this is not json")
    assertTrue(assertThrows<LlmException> { llm.chatJson("x") }.message!!.contains("not valid JSON"))

    answer("[1, 2]")
    assertThrows<LlmException> { llm.chatJson("x") }
  }

  @Test
  fun `an unreachable LLM is reported`() {
    settings.update("llm.url", "http://127.0.0.1:1/v1")

    assertTrue(assertThrows<LlmException> { llm.chat("x") }.message!!.contains("could not be reached"))
  }
}
