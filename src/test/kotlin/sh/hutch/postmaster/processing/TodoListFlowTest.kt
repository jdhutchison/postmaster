package sh.hutch.postmaster.processing

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.services.TodoListService
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts
import sh.hutch.postmaster.testsupport.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/**
 * Action items and the update_todo_list action (CLAR R5): the LLM lists what
 * an email asks for, the action writes each item to the markdown to-do list,
 * Done ticks it off there, and the to-do list check brings in what was ticked
 * off or started there (CLAR R9).
 */
@QuarkusTest
class TodoListFlowTest : PipelineTest() {
  private lateinit var todo: Path

  /** The Message-ID of this test's sold email; the tests share a database, so each has its own */
  private lateinit var sold: String

  @Inject
  lateinit var todos: TodoListService

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
    // Each test has a to-do list of its own, so earlier tests' items, with the same names, aren't in it
    dataSource.update("UPDATE Email_action_items SET todo_name = NULL")
    settings.update("todo.postmaster_url", "http://tigerbear:8080/mail/")
  }

  private fun processTurntable() {
    llm(
        SUMMARISE,
        """{"summary": "The turntable sold", "action_items": [
             {"action": "Post turntable in three days", "notes": "Sold for ${'$'}240; use the prepaid label"},
             {"action": "Leave feedback for the buyer", "due": null}
           ]}"""
    )
    rule(RuleType.ACTION, "todo", listOf(condition("sender", "contains", "ebay")), listOf(action("update_todo_list")))
    inbox(message("<$sold>", "Item sold", from = "eBay <ebay@ebay.example>", sent = Instant.parse("2026-10-25T03:00:00Z")))
    run()
  }

  @Test
  fun `the LLM's action items are stored with their notes`() {
    processTurntable()

    val items = items(sold)
    assertEquals(listOf("Post turntable in three days", "Leave feedback for the buyer"), items.map { it["description"] })
    assertEquals(listOf(null, null), items.map { it["due_date"]?.toString() })
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
    assertEquals(listOf("completed: false", "status: todo", "---"), lines.subList(5, 8))
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
  fun `an interrupted write is finished under the same name`() {
    // CLAR R6: the name is saved before the files are written
    processTurntable()
    val id = id(sold)
    Files.delete(todo.resolve("Post turntable in three days.md"))
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Post turntable in three days]] #todo-item\n", ""))
    dataSource.update("UPDATE Email_action_items SET todo_created_at = NULL WHERE description = 'Post turntable in three days' AND email_id = ?", id)
    dataSource.update("DELETE FROM Email_action_results WHERE email_id = ? AND action_type = 'update_todo_list'", id)
    dataSource.update("UPDATE Emails SET status = 'pending', progress = 'matched' WHERE id = ?", id)

    run()

    assertTrue(Files.exists(todo.resolve("Post turntable in three days.md")))
    assertFalse(Files.exists(todo.resolve("Post turntable in three days 2.md")))
    assertEquals(1, index().lines().count { it.contains("[[Post turntable in three days]]") }, index())
    assertEquals(1, index().lines().count { it.contains("[[Leave feedback for the buyer]]") }, index())
    assertTrue(items(sold).all { it["todo_created_at"] != null })
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
  fun `the to-do list check marks done what was ticked off there, and keeps the other place in step`() {
    processTurntable()
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Post turntable", "- [x] [[Post turntable"))
    val feedback = file("Leave feedback for the buyer")
    Files.writeString(todo.resolve("Leave feedback for the buyer.md"), feedback.replace("completed: false", "completed: true"))

    assertEquals(2, todos.sync())

    assertEquals(listOf(true, true), items(sold).map { it["completed"] })
    assertEquals(false, email(sold)["action_required"])
    assertTrue(file("Post turntable in three days").contains("completed: true\nstatus: done\n"))
    assertTrue(index().contains("- [x] [[Leave feedback for the buyer]]"), index())
    assertEquals(0, todos.sync(), "nothing more has changed")
  }

  @Test
  fun `processing runs no longer read the to-do list`() {
    // CLAR R9: the to-do list check runs on its own, every todo.sync_frequency
    processTurntable()
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Post turntable", "- [x] [[Post turntable"))

    run()

    assertEquals(listOf(false, false), items(sold).map { it["completed"] })
  }

  @Test
  fun `an item started in the to-do list is in progress here, and can be reopened there`() {
    processTurntable()
    val id = id(sold)
    val itemId = (items(sold)[0]["id"] as Number).toLong()
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Post turntable", "- [/] [[Post turntable"))

    todos.sync()

    assertEquals(listOf(true, false), items(sold).map { it["in_progress"] })
    assertEquals(true, email(sold)["action_required"], "an item in progress still needs doing")
    assertTrue(file("Post turntable in three days").contains("status: in-progress"))
    val row = getHtml("/emails/$id").testId("action-item-$itemId")
    assertEquals("true", row.attr("data-in-progress"))
    assertEquals("In progress", row.selectFirst(".pm-label")!!.text())

    // Started in the file instead, then done in the index, then reopened there
    val feedback = file("Leave feedback for the buyer")
    Files.writeString(todo.resolve("Leave feedback for the buyer.md"), feedback.replace("status: todo", "status: in-progress"))
    todos.sync()
    assertTrue(index().contains("- [/] [[Leave feedback for the buyer]]"), index())
    Files.writeString(todo.resolve("index.md"), index().replace("- [/] [[Post turntable", "- [x] [[Post turntable"))
    todos.sync()
    assertEquals(true, items(sold)[0]["completed"])
    Files.writeString(todo.resolve("index.md"), index().replace("- [x] [[Post turntable", "- [ ] [[Post turntable"))
    todos.sync()
    assertEquals(listOf(false, false), items(sold).map { it["completed"] }, "reopened")
    assertEquals(listOf(false, true), items(sold).map { it["in_progress"] })
    assertTrue(file("Post turntable in three days").contains("completed: false\nstatus: todo\n"))
  }

  @Test
  fun `an item the LLM gives a date is an event instead`() {
    // CLAR R9: something is an event or an action item, never both
    llm(
        SUMMARISE,
        """{"summary": "Excursion", "events": [{"title": "Zoo excursion", "when": "2026-11-05T09:00"}],
           "action_items": [
             {"action": "Pay for the excursion", "due": "2026-11-03", "notes": "Through the app"},
             {"action": "Zoo excursion", "due": "2026-11-05"},
             {"action": "Sign the form"}
           ]}"""
    )
    inbox(message("<dated-$sold>", "Zoo excursion"))

    run()

    assertEquals(listOf("Sign the form"), items("dated-$sold").map { it["description"] })
    val events = dataSource.rows(
        "SELECT v.title, v.starts_at, v.all_day, v.notes FROM Email_events v JOIN Emails e ON e.id = v.email_id " +
            "WHERE e.message_id = ? ORDER BY v.starts_at",
        "dated-$sold"
    )
    assertEquals(listOf("Pay for the excursion", "Zoo excursion"), events.map { it["title"] }, "the same event isn't added twice")
    assertEquals(true, events[0]["all_day"])
    assertEquals("Through the app", events[0]["notes"])
    assertTrue(events[0]["starts_at"].toString().startsWith("2026-11-03"), events[0]["starts_at"].toString())
  }

  @Test
  fun `the summarise prompt says something with a date is an event`() {
    processTurntable()

    val request = wireMock.allServeEvents.map { it.request.bodyAsString }.single { it.contains(SUMMARISE) }
    assertTrue(request.contains("Something is either an event or an action item, never both"), request)
    assertFalse(request.contains("\\\"due\\\""), "action items have no due date")
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

  // CLAR R11: a task to reply, and tasks added by hand

  private fun replyEmail(subject: String): String {
    llm(SUMMARISE, """{"summary": "Can you come to lunch?", "response_required": true}""")
    val messageId = "reply-$subject-$sold".replace(' ', '-')
    inbox(message("<$messageId>", subject, from = "Sam Friend <sam@friends.example>"))
    run()
    return messageId
  }

  @Test
  fun `an email that needs a reply gets a task to reply in the to-do list, linking back to it`() {
    val messageId = replyEmail("Lunch next week")
    val id = id(messageId)

    val item = items(messageId).single()
    assertEquals("Reply to Sam Friend: Lunch next week", item["description"])
    assertEquals("reply", item["kind"])
    assertEquals("Reply to Sam Friend Lunch next week", item["todo_name"], "the colon can't be in a file name")
    assertTrue(index().contains("- [ ] [[Reply to Sam Friend Lunch next week]] #todo-item"), index())
    val text = file("Reply to Sam Friend Lunch next week")
    assertTrue(text.contains("Can you come to lunch?"), text)
    assertTrue(text.contains("[Open the email in Postmaster](http://tigerbear:8080/mail/emails/$id)"), text)
    val email = email(messageId)
    assertEquals(true, email["response_required"])
    assertEquals(false, email["action_required"], "a reply isn't an action")
    val page = getHtml("/emails/$id")
    assertEquals("In the to-do list", page.testId("reply-todo").text())
    assertTrue(page.select(".pm-flag--action").isEmpty(), "the reply is shown with Reply needed, not as an item")

    processing.reprocess(id)
    assertEquals(1, items(messageId).size, "not written again")
    assertEquals(1, index().lines().count { it.contains("[[Reply to Sam Friend Lunch next week]]") })
  }

  @Test
  fun `Replied ticks the task off, and ticking it off in the to-do list means no reply is needed`() {
    val replied = replyEmail("Replied here")
    htmx().formParam("required", "false").post("/emails/${id(replied)}/response-required")

    assertTrue(index().contains("- [x] [[Reply to Sam Friend Replied here]]"), index())
    assertEquals(false, email(replied)["response_required"])

    val ticked = replyEmail("Ticked there")
    Files.writeString(todo.resolve("index.md"), index().replace("- [ ] [[Reply to Sam Friend Ticked there]]", "- [x] [[Reply to Sam Friend Ticked there]]"))
    todos.sync()
    assertEquals(false, email(ticked)["response_required"])

    // Reopened there, so a reply is needed again; and set again here, the task is reopened
    Files.writeString(todo.resolve("index.md"), index().replace("- [x] [[Reply to Sam Friend Ticked there]]", "- [ ] [[Reply to Sam Friend Ticked there]]"))
    todos.sync()
    assertEquals(true, email(ticked)["response_required"])
    assertEquals(200, htmx().formParam("required", "true").post("/emails/${id(replied)}/response-required").statusCode)
    assertEquals(true, email(replied)["response_required"])
    assertTrue(index().contains("- [ ] [[Reply to Sam Friend Replied here]]"), index())
    assertEquals(1, items(replied).size)
  }

  @Test
  fun `marking an email done clears reply needed and ticks the reply off`() {
    val messageId = replyEmail("Done with it")

    htmx().post("/emails/${id(messageId)}/done")

    assertEquals(false, email(messageId)["response_required"])
    assertTrue(index().contains("- [x] [[Reply to Sam Friend Done with it]]"), index())
  }

  @Test
  fun `a to-do task can be added to any email by name`() {
    inbox(message("<task-$sold>", "Plain email"))
    run()
    val id = id("task-$sold")
    val form = htmx().get("/emails/$id/tasks")
    assertEquals(200, form.statusCode)
    assertTrue(form.body.asString().contains("data-testid=\"task-name\""))
    assertEquals(422, htmx().formParam("name", " ").post("/emails/$id/tasks").statusCode)

    val response = htmx().formParam("name", "Book the dentist").post("/emails/$id/tasks")

    assertEquals(200, response.statusCode)
    assertEquals("/mail/emails/$id", response.header("HX-Redirect"))
    val item = items("task-$sold").single()
    assertEquals("task", item["kind"])
    assertEquals("Book the dentist", item["todo_name"])
    assertTrue(index().contains("- [ ] [[Book the dentist]] #todo-item"), index())
    assertTrue(file("Book the dentist").contains("[Open the email in Postmaster](http://tigerbear:8080/mail/emails/$id)"))
    assertEquals(true, email("task-$sold")["action_required"])
    assertEquals(409, htmx().formParam("name", "book the dentist").post("/emails/$id/tasks").statusCode, "not twice")
    assertEquals(1, getHtml("/emails/$id").select("[data-testid=add-task]").size)
  }

  @Test
  fun `a task that can't be written to the to-do list isn't added`() {
    inbox(message("<task-broken-$sold>", "Broken list"))
    run()
    val id = id("task-broken-$sold")
    Files.writeString(todo.resolve("index.md"), "# To-do\n")

    val response = htmx().formParam("name", "Call the plumber").post("/emails/$id/tasks")

    assertEquals(502, response.statusCode)
    assertTrue(items("task-broken-$sold").isEmpty())
  }
}
