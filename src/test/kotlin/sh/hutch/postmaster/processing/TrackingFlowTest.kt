package sh.hutch.postmaster.processing

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.mail.Flags
import jakarta.mail.Folder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.services.FolderService
import sh.hutch.postmaster.services.TrackingService
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts
import sh.hutch.postmaster.testsupport.update
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tracking emails, marking them done, events or action items, and the
 * mailbox's folders (CLAR R9).
 */
@QuarkusTest
class TrackingFlowTest : PipelineTest() {
  @Inject
  lateinit var tracking: TrackingService

  @Inject
  lateinit var folders: FolderService

  private lateinit var todo: Path

  /** This test's Message-IDs share a prefix; the tests share a database */
  private lateinit var prefix: String

  private fun run() = processing.runNow(RunTrigger.MANUAL)!!

  private fun id(messageId: String) = (email(messageId)["id"] as Number).toLong()

  private fun items(messageId: String): List<Map<String, Any?>> =
      dataSource.rows(
          "SELECT i.* FROM Email_action_items i JOIN Emails e ON e.id = i.email_id WHERE e.message_id = ? ORDER BY i.position",
          messageId
      )

  private fun index() = Files.readString(todo.resolve("index.md"))

  /** Moves the message with [subject] from one folder to another, as a mail client would */
  private fun moveOutside(subject: String, from: String, to: String) {
    TestMessages.withStore(greenMail) { store ->
      val source = store.getFolder(from)
      source.open(Folder.READ_WRITE)
      try {
        val message = source.messages.single { it.subject == subject }
        source.copyMessages(arrayOf(message), store.getFolder(to))
        message.setFlag(Flags.Flag.DELETED, true)
      } finally {
        source.close(true)
      }
    }
  }

  /** Processes one message with [subject], received just now unless [sent] says, and returns its Message-ID */
  private fun processed(subject: String, sent: Instant = Instant.now()): String {
    val messageId = "$prefix-${subject.replace(' ', '-')}@mail.example"
    inbox(message("<$messageId>", subject, sent = sent))
    run()
    return messageId
  }

  @BeforeEach
  fun setUpTracking() {
    prefix = "track-${UUID.randomUUID()}"
    todo = Files.createTempDirectory("postmaster-todo")
    Files.writeString(todo.resolve("index.md"), "# To-do\n\n<!-- todo-items:end -->\n")
    settings.update("todo.path", todo.toString())
    settings.update("tracking.window", "3650d")
    // Each test has a to-do list of its own, so earlier tests' items, with the same names, aren't in it
    dataSource.update("UPDATE Email_action_items SET todo_name = NULL")
  }

  // Tracking

  @Test
  fun `an email moved or deleted outside Postmaster is no longer tracked`() {
    val kept = processed("Kept")
    val moved = processed("Moved away")
    val deleted = processed("Thrown away")
    moveOutside("Moved away", "INBOX", "Folders/School")
    moveOutside("Thrown away", "INBOX", "Trash")

    val result = tracking.check()

    assertTrue(result.checked >= 3)
    assertEquals("tracked", email(kept)["tracking"])
    assertEquals("moved", email(moved)["tracking"])
    assertEquals("deleted", email(deleted)["tracking"])
    assertEquals("Trash", email(deleted)["folder"])
    assertTrue(email(moved)["tracking_changed_at"] != null)
    assertTrue(email(kept)["tracking_checked_at"] != null)
  }

  @Test
  fun `emails received before the tracking window aren't looked for`() {
    val old = processed("Old news", Instant.now().minus(Duration.ofDays(30)))
    settings.update("tracking.window", "1d")
    moveOutside("Old news", "INBOX", "Folders/School")

    tracking.check()

    assertEquals("tracked", email(old)["tracking"])
  }

  @Test
  fun `nothing changes if the mailbox can't be reached`() {
    val messageId = processed("Unreachable")
    moveOutside("Unreachable", "INBOX", "Folders/School")
    settings.update("imap.port", "1")

    val failed = runCatching { tracking.check() }

    assertTrue(failed.isFailure)
    assertEquals("tracked", email(messageId)["tracking"])
  }

  @Test
  fun `untracked emails are left out of the list unless asked for, and are greyed out`() {
    val kept = processed("Still here")
    val moved = processed("Gone elsewhere")
    moveOutside("Gone elsewhere", "INBOX", "Folders/School")
    tracking.check()
    val keptId = id(kept)
    val movedId = id(moved)

    val list = getHtml("/emails")
    assertEquals(1, list.select("[data-testid=email-row-$keptId]").size)
    assertEquals(0, list.select("[data-testid=email-row-$movedId]").size)

    val all = getHtml("/emails?untracked=true")
    val row = all.testId("email-row-$movedId")
    assertEquals("moved", row.attr("data-untracked"))
    assertTrue(row.hasClass("pm-row--untracked"))
    assertEquals("Moved", row.select("[data-testid=tracking-badge]").text())
    assertTrue(all.testId("filter-untracked").hasAttr("checked"))
  }

  @Test
  fun `an untracked email can be viewed but not acted on`() {
    val messageId = processed("Viewed only")
    moveOutside("Viewed only", "INBOX", "Trash")
    tracking.check()
    val id = id(messageId)

    val page = getHtml("/emails/$id")
    assertEquals("deleted", page.selectFirst(".pm-page--inspector")!!.attr("data-untracked"))
    assertTrue(page.testId("untracked-notice").text().startsWith("Deleted"))
    assertTrue(page.testId("delete-email").hasAttr("disabled"))
    assertTrue(page.testId("move-email").hasAttr("disabled"))
    assertTrue(page.testId("email-type").hasAttr("disabled"))
    assertEquals(0, page.select("[data-testid=mark-done]").size)

    val response = htmx().formParam("read", "true").post("/emails/$id/read")
    assertEquals(409, response.statusCode)
    assertEquals("error", response.toasts().single().first)
    assertEquals(409, htmx().delete("/emails/$id").statusCode)
    assertEquals(409, htmx().post("/emails/$id/done").statusCode)
    assertTrue(actionResults(messageId).none { it["rule_name"] == null }, "nothing is recorded")
  }

  @Test
  fun `deleting an email keeps it, as deleted`() {
    val messageId = processed("Delete me")
    val id = id(messageId)

    assertEquals(200, htmx().delete("/emails/$id").statusCode)

    assertEquals("deleted", email(messageId)["tracking"])
    assertEquals(false, email(messageId)["deleted"])
    assertTrue(getHtml("/emails/$id").testId("untracked-notice").text().startsWith("Deleted"))
  }

  @Test
  fun `a delete action, or a move to the trash folder, makes the email deleted`() {
    rule(RuleType.ACTION, "bin", listOf(condition("subject", "contains", "Binned")), listOf(action("move", destination = "Trash")))

    val messageId = processed("Binned")

    assertEquals("deleted", email(messageId)["tracking"])
    assertEquals(listOf("Binned"), TestMessages.folderContents(greenMail, "Trash").map { it.first })
  }

  // Done

  @Test
  fun `marking an email done completes its items, clears reply needed, and hides it until it's reopened`() {
    llm(SUMMARISE, """{"summary": "S", "response_required": true, "action_items": [{"action": "Sign the form"}]}""")
    rule(RuleType.ACTION, "todo", listOf(condition("subject", "contains", "Finish")), listOf(action("update_todo_list")))
    val messageId = processed("Finish me")
    val id = id(messageId)
    assertEquals(1, getHtml("/emails/$id").select("[data-testid=mark-done]").size)

    val response = htmx().post("/emails/$id/done")

    assertEquals(200, response.statusCode)
    val email = email(messageId)
    assertEquals("done", email["tracking"])
    assertEquals(false, email["response_required"])
    assertEquals(false, email["action_required"])
    assertEquals(listOf(true), items(messageId).map { it["completed"] })
    assertTrue(index().contains("- [x] [[Sign the form]]"), index())
    assertEquals(0, getHtml("/emails").select("[data-testid=email-row-$id]").size)
    val page = getHtml("/emails/$id")
    assertTrue(page.testId("untracked-notice").text().startsWith("Done: nothing more needs doing"))
    assertTrue(page.testId("mark-read").hasAttr("disabled") || page.testId("mark-unread").hasAttr("disabled"))

    assertEquals(200, htmx().post("/emails/$id/reopen").statusCode)

    assertEquals("tracked", email(messageId)["tracking"])
    assertEquals(listOf(true), items(messageId).map { it["completed"] }, "its items stay done")
    assertEquals(1, getHtml("/emails").select("[data-testid=email-row-$id]").size)
    assertEquals(409, htmx().post("/emails/$id/reopen").statusCode, "only a done email can be reopened")
  }

  // Events or action items

  @Test
  fun `an event that isn't in a calendar can be made an action item, written to the to-do list if it's used`() {
    llm(SUMMARISE, """{"summary": "S", "events": [{"title": "Parent interviews", "when": "2026-11-12T16:30", "location": "Room 4"}]}""")
    rule(RuleType.ACTION, "todo", listOf(condition("subject", "contains", "Interviews")), listOf(action("update_todo_list")))
    val messageId = processed("Interviews")
    val id = id(messageId)
    val eventId = (events(messageId).single()["id"] as Number).toLong()
    val form = parseHtml(htmx().get("/emails/$id/events/$eventId/calendar").body.asString())
    assertEquals(1, form.select("[data-testid=event-to-action-item]").size)

    val response = htmx().post("/emails/$id/events/$eventId/action-item")

    assertEquals(200, response.statusCode)
    assertTrue(events(messageId).isEmpty())
    val item = items(messageId).single()
    assertEquals("Parent interviews", item["description"])
    assertEquals("2026-11-12", item["due_date"].toString())
    assertEquals("At 16:30\nWhere: Room 4", item["notes"])
    assertEquals("Parent interviews", item["todo_name"])
    assertTrue(index().contains("- [ ] [[Parent interviews]]"), index())
    assertEquals(true, email(messageId)["action_required"])
  }

  @Test
  fun `an action item can be made an event, which takes it out of the to-do list and can add it to a calendar`() {
    calendar("Family-and-Kids")
    llm(SUMMARISE, """{"summary": "S", "action_items": [{"action": "Book the dentist", "notes": "Ask for Dr Lee"}, {"action": "Pay"}]}""")
    rule(RuleType.ACTION, "todo", listOf(condition("subject", "contains", "Dentist")), listOf(action("update_todo_list")))
    val messageId = processed("Dentist")
    val id = id(messageId)
    val itemId = (items(messageId)[0]["id"] as Number).toLong()
    val calendarId = calendars.list().single { it.name == "Family-and-Kids" }.id
    val form = parseHtml(htmx().get("/emails/$id/action-items/$itemId/event").body.asString())
    assertEquals(1, form.select("[data-testid=item-event-date]").size)
    assertEquals(422, htmx().formParam("date", "").post("/emails/$id/action-items/$itemId/event").statusCode)

    val response = htmx().formParam("date", "2026-11-20").formParam("time", "10:15").formParam("calendarId", calendarId.toString())
        .post("/emails/$id/action-items/$itemId/event")

    assertEquals(200, response.statusCode)
    assertEquals(listOf("Pay"), items(messageId).map { it["description"] })
    val event = events(messageId).single()
    assertEquals("Book the dentist", event["title"])
    assertEquals(false, event["all_day"])
    assertTrue(event["starts_at"].toString().startsWith("2026-11-20"))
    assertEquals(true, event["is_added"])
    assertEquals("Ask for Dr Lee", event["notes"])
    assertFalse(index().contains("Book the dentist"), index())
    assertFalse(Files.exists(todo.resolve("Book the dentist.md")))
    assertTrue(index().contains("- [ ] [[Pay]]"))
  }

  // Folders

  @Test
  fun `the folders are scanned and offered for moving and in the rule form`() {
    TestMessages.createFolders(greenMail, "Folders/Receipts")

    val found = folders.scan()

    assertTrue(found.containsAll(listOf("INBOX", "Trash", "Folders/Bills", "Folders/Receipts")), found.toString())
    val messageId = processed("Where to")
    val id = id(messageId)
    val dialog = parseHtml(htmx().get("/emails/$id/move").body.asString())
    val options = dialog.select("[data-testid=move-folder] option").map { it.attr("value") }
    assertTrue(options.contains("Folders/Receipts"))
    assertFalse(options.contains("INBOX"), "its own folder isn't offered")

    TestMessages.createFolders(greenMail, "Folders/New")
    val refreshed = htmx().post("/emails/$id/move/folders")
    assertEquals(200, refreshed.statusCode)
    assertTrue(parseHtml(refreshed.body.asString()).select("[data-testid=move-folder] option").map { it.attr("value") }.contains("Folders/New"))

    val rule = rule(RuleType.ACTION, "file it", listOf(condition("subject", "contains", "x")), listOf(action("move", destination = "Old/Gone")))
    val formPage = getHtml("/rules/action/${rule.id}/edit")
    val select = formPage.selectFirst("[data-testid=action-folder]")!!
    assertTrue(select.select("option").map { it.attr("value") }.contains("Folders/New"))
    assertEquals("Old/Gone (not found)", select.selectFirst("option[selected]")!!.text())
  }
}
