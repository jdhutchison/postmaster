package sh.hutch.postmaster.processing

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/**
 * Action items and the update_todo_list action (CLAR R5): the LLM lists what
 * an email asks for, the action writes each item to the markdown to-do list,
 * Done ticks it off there, and each processing run marks done here what was
 * ticked off there.
 */
@QuarkusTest
class TodoListFlowTest : PipelineTest() {
  private lateinit var todo: Path

  /** The Message-ID of this test's sold email; the tests share a database, so each has its own */
  private lateinit var sold: String

  private fun run() = processing.runNow(RunTrigger.MANUAL)!!

  private fun id(messageId: String) = (email(messageId)["id"] as Number).toLong()

  private fun items(messageId: String): List<Map<String, Any?>> =
      dataSource.rows(
          "SELECT i.* FROM Email_action_items i JOIN Emails e ON e.id = i.email_id WHERE e.message_id = ? ORDER BY i.position",
          messageId
      )

  private fun index() = Files.readString(todo.resolve("index.md"))

  private fun file(name: String) = Files.readString(todo.resolve("$name.md"))

  @BeforeEach
  fun setUpTodoList() {
    todo = Files.createTempDirectory("postmaster-todo")
    sold = "todo-sold-${UUID.randomUUID()}@mail.example"
    Files.writeString(todo.resolve("index.md"), "# To-do\n\n<!-- todo-items:end -->\n")
    settings.update("todo.path", todo.toString())
    settings.update("todo.postmaster_url", "http://tigerbear:8080/mail/")
  }

  private fun processTurntable() {
    llm(
        SUMMARISE,
        """{"summary": "The turntable sold", "action_items": [
             {"action": "Post turntable in three days", "due": "2026-10-28", "notes": "Sold for ${'$'}240; use the prepaid label"},
             {"action": "Leave feedback for the buyer", "due": null}
           ]}"""
    )
    rule(RuleType.ACTION, "todo", listOf(condition("sender", "contains", "ebay")), listOf(action("update_todo_list")))
    inbox(message("<$sold>", "Item sold", from = "eBay <ebay@ebay.example>", sent = Instant.parse("2026-10-25T03:00:00Z")))
    run()
  }

  @Test
  fun `the LLM's action items are stored with their due dates and notes`() {
    processTurntable()

    val items = items(sold)
    assertEquals(listOf("Post turntable in three days", "Leave feedback for the buyer"), items.map { it["description"] })
    assertEquals(listOf("2026-10-28", null), items.map { it["due_date"]?.toString() })
    assertEquals("Sold for \$240; use the prepaid label", items[0]["notes"])
    val email = email(sold)
    assertEquals(true, email["action_required"])
    assertEquals("Post turntable in three days; Leave feedback for the buyer", email["action_description"])
  }

  @Test
  fun `the summarise prompt asks for action items and gives the received date`() {
    processTurntable()

    val request = wireMock.allServeEvents.map { it.request.bodyAsString }.single { it.contains(SUMMARISE) }
    assertTrue(request.contains("action_items"), "asks for a list of action items")
    assertTrue(Regex("Received: [A-Z][a-z]+day \\d{1,2} [A-Z][a-z]+ \\d{4}, \\d\\d:\\d\\d").containsMatchIn(request), "the received time")
  }

  @Test
  fun `update_todo_list writes a file for each item and lists it in the index`() {
    processTurntable()

    val id = id(sold)
    val text = file("Post turntable in three days")
    val lines = text.lines()
    assertEquals(listOf("---", "todo-item: true", "tags:", "  - todo-item"), lines.take(4))
    assertTrue(Regex("created: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d").matches(lines[4]), lines[4])
    assertEquals(listOf("completed: false", "due: 2026-10-28", "---"), lines.subList(5, 8), "due is the second last line")
    assertTrue(text.contains("Sold for \$240; use the prepaid label"), text)
    assertTrue(text.contains("Email: Item sold from eBay"), text)
    assertTrue(text.contains("[Open the email in Postmaster](http://tigerbear:8080/mail/emails/$id)"), text)
    assertFalse(file("Leave feedback for the buyer").contains("due:"))
    assertEquals(
        "# To-do\n\n- [ ] [[Post turntable in three days]] #todo-item\n- [ ] [[Leave feedback for the buyer]] #todo-item\n<!-- todo-items:end -->\n",
        index()
    )
    val items = items(sold)
    assertEquals(listOf("Post turntable in three days", "Leave feedback for the buyer"), items.map { it["todo_name"] })
    assertTrue(items.all { it["todo_created_at"] != null })
    val result = actionResults(sold).single { it["action_type"] == "update_todo_list" }
    assertEquals("done", result["status"])
  }

  @Test
  fun `reprocessing doesn't write the items again`() {
    processTurntable()
    val before = index()

    processing.reprocess(id(sold))

    assertEquals(before, index())
    assertEquals(2, items(sold).size)
    val results = actionResults(sold).filter { it["action_type"] == "update_todo_list" }
    assertEquals("skipped", results.single()["status"])
  }

  @Test
  fun `without action items the action is skipped`() {
    rule(RuleType.ACTION, "todo", listOf(condition("subject", "contains", "Nothing")), listOf(action("update_todo_list")))
    inbox(message("<todo-none-$sold>", "Nothing to do"))

    run()

    assertEquals("skipped", actionResults("todo-none-$sold").single()["status"])
    assertEquals("# To-do\n\n<!-- todo-items:end -->\n", index())
  }

  @Test
  fun `a broken index fails the action and is reported`() {
    Files.writeString(todo.resolve("index.md"), "# To-do\n")

    processTurntable()

    val result = actionResults(sold).single { it["action_type"] == "update_todo_list" }
    assertEquals("failed", result["status"])
    assertTrue(errors(sold).any { (it["message"] as String).contains("<!-- todo-items:end -->") })
    assertFalse(Files.exists(todo.resolve("Post turntable in three days.md")))
  }

  @Test
  fun `the old single action still becomes an item`() {
    // A summarise prompt edited before R5 answers with action_required and action
    llm(SUMMARISE, """{"summary": "S", "action_required": true, "action": "Sign the form"}""")
    inbox(message("<todo-old-$sold>", "Old format"))

    run()

    assertEquals(listOf("Sign the form"), items("todo-old-$sold").map { it["description"] })
  }

  @Test
  fun `Done on the email page ticks the item off in the to-do list`() {
    processTurntable()
    val id = id(sold)
    val itemId = (items(sold)[0]["id"] as Number).toLong()
    val page = getHtml("/emails/$id")
    assertEquals(
        "Post turntable in three days",
        page.testId("action-item-$itemId").select("[data-testid=action-item-description]").text()
    )
    assertEquals(1, page.select("[data-testid=complete-action-item-$itemId]").size)

    val response = htmx().post("/emails/$id/action-items/$itemId/done")

    assertEquals(200, response.statusCode)
    assertEquals("/mail/emails/$id", response.header("HX-Redirect"))
    assertTrue(index().contains("- [x] [[Post turntable in three days]] #todo-item"), index())
    assertTrue(index().contains("- [ ] [[Leave feedback for the buyer]] #todo-item"), index())
    assertTrue(file("Post turntable in three days").lines().contains("completed: true"))
    val item = items(sold)[0]
    assertEquals(true, item["completed"])
    assertNotNull(item["completed_at"])
    assertEquals("Leave feedback for the buyer", email(sold)["action_description"])
    val after = getHtml("/emails/$id")
    assertEquals("true", after.testId("action-item-$itemId").attr("data-completed"))
    assertEquals(0, after.select("[data-testid=complete-action-item-$itemId]").size)
    assertEquals(404, htmx().post("/emails/$id/action-items/999999/done").statusCode)
  }

  @Test
  fun `processing marks done what was ticked off in the to-do list`() {
    processTurntable()
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Post turntable", "- [x] [[Post turntable"))
    val feedback = file("Leave feedback for the buyer")
    Files.writeString(todo.resolve("Leave feedback for the buyer.md"), feedback.replace("completed: false", "completed: true"))

    run()

    assertEquals(listOf(true, true), items(sold).map { it["completed"] })
    assertEquals(false, email(sold)["action_required"])
  }

  @Test
  fun `a Done that can't update the to-do list changes nothing`() {
    processTurntable()
    val id = id(sold)
    val itemId = (items(sold)[0]["id"] as Number).toLong()
    val writable = Files.getPosixFilePermissions(todo)
    Files.setPosixFilePermissions(todo, PosixFilePermissions.fromString("r-xr-xr-x"))
    val response = try {
      htmx().post("/emails/$id/action-items/$itemId/done")
    } finally {
      Files.setPosixFilePermissions(todo, writable)
    }

    assertEquals(502, response.statusCode)
    assertEquals("error", response.toasts().single().first)
    assertEquals(false, items(sold)[0]["completed"])
    assertTrue(index().contains("- [ ] [[Post turntable in three days]]"))
  }
}
