package sh.hutch.postmaster.services

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.util.IsoDurations
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

/**
 * Calls the LLM through its OpenAI-compatible chat completions API (llama.cpp on kodiakbear,
 * CLARIFICATIONS.md Q4), using the llm.* settings.
 */
@ApplicationScoped
class LlmClient(private val settings: SettingsService) {
  private val json = ObjectMapper()
  private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

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
    val response = try {
      http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: HttpTimeoutException) {
      throw LlmException("LLM request timed out after ${IsoDurations.format(timeout)}", e)
    } catch (e: IOException) {
      throw LlmException("LLM could not be reached at $url: ${e.message ?: e.javaClass.simpleName}", e)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw LlmException("LLM request was interrupted", e)
    }
    if (response.statusCode() !in 200..299) {
      throw LlmException("LLM returned HTTP ${response.statusCode()}: ${response.body().orEmpty().take(200)}")
    }
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

  /** The model's answer to [prompt] as a JSON object. */
  fun chatJson(prompt: String): JsonNode = parseObject(chat(prompt, json = true))

  companion object {
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
