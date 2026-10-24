package sh.hutch.postmaster.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.mail.Flags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.processing.PipelineTest
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.message
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The MCP server, used as Hermes would: JSON-RPC over Streamable HTTP at /mail/mcp (REQ "MCP server"; CLAR D13). */
@QuarkusTest
class McpToolsTest : PipelineTest() {
  private val json = ObjectMapper()

  /** A minimal MCP client: initialises a session, then lists and calls tools */
  private inner class Client {
    private val ids = AtomicInteger()
    private val session: String

    init {
      val response = post(
          request("initialize", mapOf(
              "protocolVersion" to "2025-06-18",
              "capabilities" to emptyMap<String, Any>(),
              "clientInfo" to mapOf("name" to "postmaster-test", "version" to "1")
          )),
          null
      )
      session = response.first ?: error("no Mcp-Session-Id")
      assertTrue(response.second.path("result").path("capabilities").has("tools"), "the server offers tools: ${response.second}")
      post(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"), session)
    }

    fun tools(): List<String> = post(request("tools/list", emptyMap<String, Any>()), session).second
        .path("result").path("tools").map { it.path("name").asText() }

    /** Calls [name]; returns the parsed text content and whether it's an error */
    fun call(name: String, arguments: Map<String, Any?> = emptyMap()): Pair<JsonNode, Boolean> {
      val result = post(request("tools/call", mapOf("name" to name, "arguments" to arguments)), session).second.path("result")
      val text = result.path("content").first().path("text").asText()
      val parsed = runCatching { json.readTree(text) }.getOrNull()?.takeIf { it.isContainerNode } ?: json.valueToTree(text)
      return parsed to result.path("isError").asBoolean(false)
    }

    private fun request(method: String, params: Any) =
        mapOf("jsonrpc" to "2.0", "id" to ids.incrementAndGet(), "method" to method, "params" to params)

    private fun post(body: Any, session: String?): Pair<String?, JsonNode> {
      val spec = given()
          .contentType("application/json")
          .header("Accept", "application/json, text/event-stream")
          .body(json.writeValueAsString(body))
      session?.let { spec.header("Mcp-Session-Id", it) }
      val response = spec.post("/mcp")
      assertTrue(response.statusCode in 200..202, "HTTP ${response.statusCode}: ${response.asString()}")
      val text = response.asString()
      // A JSON body, or server-sent events whose data is the JSON-RPC response
      val payload = if (response.contentType.startsWith("text/event-stream")) {
        text.lines().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:").trim() }
      } else {
        text
      }
      return response.header("Mcp-Session-Id") to (if (payload.isBlank()) json.createObjectNode() else json.readTree(payload))
    }
  }

  @Test
  fun `the tools are listed`() {
    val tools = Client().tools()
    assertTrue(
        tools.containsAll(listOf(
            "search_emails", "get_email", "set_email_type", "move_email", "mark_email_read", "delete_email", "forward_email",
            "set_response_required", "set_action_required", "set_event_calendar", "reprocess_email",
            "list_types", "list_folders", "list_calendars"
        )),
        "tools: $tools"
    )
  }

  @Test
  fun `emails can be searched and read, and acted on`() {
    llm(SUMMARISE, """{"summary": "From the MCP test", "action_required": true, "action": "Pay"}""")
    inbox(message("<mcp@mail.example>", "MCP subject", from = "Mcp Sender <sender@mcp.example>", sent = Instant.parse("2026-10-21T00:00:00Z")))
    processing.runNow(RunTrigger.MANUAL)
    val client = Client()

    val (search, searchError) = client.call("search_emails", mapOf("sender" to "mcp.example"))
    assertFalse(searchError)
    assertEquals(1, search.path("total").asInt())
    val row = search.path("emails").first()
    assertEquals("MCP subject", row.path("subject").asText())
    val id = row.path("id").asLong()

    val (detail, _) = client.call("get_email", mapOf("id" to id))
    assertEquals("From the MCP test", detail.path("summary").asText())
    assertTrue(detail.path("actionRequired").asBoolean())
    assertEquals("2026-10-21T00:00:00Z", detail.path("receivedAt").asText(), "times are ISO 8601")

    val (types, _) = client.call("list_types")
    assertTrue(types.path("types").map { it.path("name").asText() }.contains("Bill"))
    val (typed, typeError) = client.call("set_email_type", mapOf("id" to id, "type" to "bill"))
    assertFalse(typeError, typed.toString())
    val (afterType, _) = client.call("get_email", mapOf("id" to id))
    assertEquals("Bill", afterType.path("type").asText())
    assertFalse(afterType.path("actionRequired").asBoolean(), "setting Bill clears action required")

    client.call("mark_email_read", mapOf("id" to id, "read" to true))
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))

    val (folders, _) = client.call("list_folders")
    assertTrue(folders.path("folders").map { it.asText() }.contains("Folders/Bills"))
    val (_, moveError) = client.call("move_email", mapOf("id" to id, "folder" to "Folders/Bills"))
    assertFalse(moveError)
    assertEquals(listOf("MCP subject"), TestMessages.folderContents(greenMail, "Folders/Bills").map { it.first })

    client.call("forward_email", mapOf("id" to id, "to" to "someone@elsewhere.example"))
    assertTrue(greenMail.receivedMessages.any { it.subject == "Fwd: MCP subject" })

    val (_, deleteError) = client.call("delete_email", mapOf("id" to id))
    assertFalse(deleteError)
    assertEquals(listOf("MCP subject"), TestMessages.folderContents(greenMail, "Trash").map { it.first })
    assertEquals(0, client.call("search_emails", mapOf("sender" to "mcp.example")).first.path("total").asInt())

    val sources = actionResults("mcp@mail.example").filter { it["rule_name"] == null }.map { it["action_type"] }
    assertEquals(listOf("set_type", "mark_read", "move", "forward_email", "delete"), sources)
  }

  @Test
  fun `bad arguments and failures come back as tool errors`() {
    val client = Client()
    assertTrue(client.call("get_email", mapOf("id" to 999999)).second, "unknown email")
    assertTrue(client.call("search_emails", mapOf("type" to "No such type")).second)
    assertTrue(client.call("search_emails", mapOf("from" to "yesterday")).second)
    assertTrue(client.call("set_event_calendar", mapOf("email_id" to 1, "event_id" to 1, "calendar" to "Nowhere")).second)
  }
}
