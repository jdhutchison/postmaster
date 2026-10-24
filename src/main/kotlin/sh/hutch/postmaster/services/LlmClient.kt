package sh.hutch.postmaster.services

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.util.IsoDurations
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Calls the LLM through its OpenAI-compatible chat completions API (llama.cpp on kodiakbear,
 * CLARIFICATIONS.md Q4), using the llm.* settings.
 */
@ApplicationScoped
class LlmClient(private val settings: SettingsService) {
  private val json = ObjectMapper()
  private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  /** How long to wait before each retry when the LLM can't be reached or is busy (CLARIFICATIONS.md R6). Tests shorten it. */
  var retryDelays: List<Duration> = listOf(Duration.ofSeconds(2), Duration.ofSeconds(10))

  /** The model's answer to [prompt]; with [json] the model is asked for a JSON object. */
  fun chat(prompt: String, json: Boolean = false): String {
    val url = settings.getString(SettingKeys.LLM_URL).orEmpty().trimEnd('/') + "/chat/completions"
    val timeout = settings.getDuration(SettingKeys.LLM_TIMEOUT)
    val body = this.json.createObjectNode().apply {
      put("model", settings.getString(SettingKeys.LLM_MODEL).orEmpty())
      putArray("messages").addObject().put("role", "user").put("content", prompt)
      put("temperature", 0)
      put("stream", false)
      // Qwen models think aloud by default; it only slows answers down here
      putObject("chat_template_kwargs").put("enable_thinking", false)
      if (json) {
        putObject("response_format").put("type", "json_object")
      }
    }
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .apply { settings.getString(SettingKeys.LLM_API_KEY)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
        .POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(body)))
        .build()
    val response = send(request, url, timeout)
    val content = try {
      this.json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText("")
    } catch (e: JacksonException) {
      throw LlmException("LLM response is not valid JSON: ${e.originalMessage}", e)
    }
    val answer = THINKING.replace(content, "").trim()
    if (answer.isEmpty()) {
      throw LlmException("LLM response had no content")
    }
    return answer
  }

  /**
   * Sends [request], trying again after each of [retryDelays] when the LLM can't be reached or is
   * busy (HTTP 429 or 5xx). A timeout isn't retried, as it has already taken llm.timeout.
   */
  private fun send(request: HttpRequest, url: String, timeout: Duration): HttpResponse<String> {
    var attempt = 0
    while (true) {
      val failure = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) {
          return response
        }
        val busy = response.statusCode() == 429 || response.statusCode() >= 500
        LlmException("LLM returned HTTP ${response.statusCode()}: ${response.body().orEmpty().take(200)}", transient = busy)
      } catch (e: HttpTimeoutException) {
        throw LlmException("LLM request timed out after ${IsoDurations.format(timeout)}", e, transient = true)
      } catch (e: IOException) {
        LlmException("LLM could not be reached at $url: ${e.message ?: e.javaClass.simpleName}", e, transient = true)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw LlmException("LLM request was interrupted", e)
      }
      if (!failure.transient || attempt >= retryDelays.size) {
        throw failure
      }
      LOG.warnf("%s; trying again in %s", failure.message, IsoDurations.format(retryDelays[attempt]))
      try {
        Thread.sleep(retryDelays[attempt].toMillis())
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
      }
      attempt++
    }
  }

  /**
   * Asks the LLM server which models it has (`GET <llm.url>/models`), giving up after [timeout]
   * without retrying: whether it's up, for the status endpoint (CLARIFICATIONS.md R7). Returns the
   * models' ids.
   *
   * @throws LlmException if it can't be reached or answers with an error
   */
  fun checkModels(timeout: Duration): List<String> {
    val url = settings.getString(SettingKeys.LLM_URL).orEmpty().trimEnd('/') + "/models"
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .apply { settings.getString(SettingKeys.LLM_API_KEY)?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
        .GET()
        .build()
    val response = try {
      HttpClient.newBuilder().connectTimeout(timeout).build().send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: HttpTimeoutException) {
      throw LlmException("No answer from $url within ${IsoDurations.format(timeout)}", e, transient = true)
    } catch (e: IOException) {
      throw LlmException("LLM could not be reached at $url: ${e.message ?: e.javaClass.simpleName}", e, transient = true)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw LlmException("The check was interrupted", e)
    }
    if (response.statusCode() !in 200..299) {
      throw LlmException("LLM returned HTTP ${response.statusCode()} for $url", transient = true)
    }
    return runCatching { json.readTree(response.body()).path("data").mapNotNull { it.path("id").asText(null) } }.getOrDefault(emptyList())
  }

  /** The model's answer to [prompt] as a JSON object. */
  fun chatJson(prompt: String): JsonNode = parseObject(chat(prompt, json = true))

  companion object {
    private val LOG = Logger.getLogger(LlmClient::class.java)
    private val THINKING = Regex("(?s)<think>.*?</think>")
    private val FENCE = Regex("(?s)\\s*```(?:json)?\\s*(.*?)\\s*```\\s*")
    private val mapper = ObjectMapper()

    /** Parses an answer that must be a JSON object, after removing any ```json fence around it. */
    fun parseObject(answer: String): JsonNode {
      val text = FENCE.matchEntire(answer)?.groupValues?.get(1) ?: answer
      val node = try {
        mapper.readTree(text)
      } catch (e: JacksonException) {
        throw LlmException("LLM response is not valid JSON: ${e.originalMessage}", e)
      }
      if (node == null || !node.isObject) {
        throw LlmException("LLM response is not valid JSON: expected an object")
      }
      return node
    }
  }
}
