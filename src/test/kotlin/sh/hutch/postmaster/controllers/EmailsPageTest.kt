package sh.hutch.postmaster.controllers

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.mail.Flags
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.processing.PipelineTest
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.Attachment
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts
import sh.hutch.postmaster.testsupport.update
import java.time.Instant

/** The emails list and email detail pages (REQ "Viewing emails", "Viewing an individual email"). */
@QuarkusTest
class EmailsPageTest : PipelineTest() {
  private fun run() = processing.runNow(RunTrigger.MANUAL)!!

  private fun id(messageId: String) = (email(messageId)["id"] as Number).toLong()

  @Test
  fun `the list shows the most recent emails with their counts, and highlights errors`() {
    llm(SUMMARISE, """{"summary": "S", "response_required": true, "events": [{"title": "Fete", "when": "2026-11-20"}]}""")
    inbox(
        message("<list-a@mail.example>", "Older one", sent = Instant.parse("2026-12-01T09:00:00Z")),
        message(
            "<list-b@mail.example>", "Newer one", sent = Instant.parse("2026-12-02T09:00:00Z"),
            attachments = listOf(Attachment("bill.pdf", "application/pdf", TestMessages.PDF))
        )
    )
    run()
    dataSource.update(
        "INSERT INTO Email_errors (email_id, phase, message, created_at) VALUES (?, 'action', 'Boom', CURRENT_TIMESTAMP)",
        id("list-a@mail.example")
    )

    val page = getHtml("/emails")

    val rows = page.select("[data-testid^=email-row-]")
    val a = id("list-a@mail.example")
    val b = id("list-b@mail.example")
    val ids = rows.map { it.attr("data-testid").removePrefix("email-row-").toLong() }
    assertTrue(ids.indexOf(b) < ids.indexOf(a), "newest first: $ids")
    val newer = page.testId("email-row-$b")
    assertEquals("Newer one", newer.select("[data-col=subject]").text())
    assertEquals("Sender", newer.select("[data-col=sender]").text())
    assertEquals("", newer.select("[data-col=status]").text(), "only statuses other than processed are shown")
    assertEquals("1", newer.select("[data-testid=count-events]").text())
    assertEquals("1", newer.select("[data-testid=count-attachments]").text())
    assertTrue(newer.select("[data-testid=error-count]").isEmpty(), "nothing shown without errors")
    assertEquals(1, newer.select("[data-testid=needs-response]").size)
    assertFalse(newer.hasClass("pm-row--error"))
    assertTrue(newer.hasClass("pm-row--unread"), "unread emails stand out")
    val older = page.testId("email-row-$a")
    assertTrue(older.hasClass("pm-row--error"), "rows with errors are highlighted")
    assertEquals("1", older.select("[data-testid=error-count]").text())
    assertEquals("0", older.select("[data-testid=count-attachments]").text())
    assertEquals("/mail/emails/$a", older.attr("data-pm-href"))
    assertTrue(page.testId("email-count").text().startsWith("The "), "unfiltered: the most recent")
    assertTrue(page.select("[data-testid=pagination]").isEmpty())
  }

  @Test
  fun `filters narrow the list and results are paginated in fifties`() {
    // Other pipeline tests share the database, so every query here is limited to this sender domain
    val bill = types.findBill()!!
    rule(RuleType.CLASSIFICATION, "bills", listOf(condition("subject", "starts_with", "Bill")), listOf(action("set_type", typeName = bill.name)))
    inbox(*(1..52).map { message("<page-$it@mail.example>", "Bill $it", from = "Power Co <power@paging.example>") }.toTypedArray())
    inbox(message("<page-other@mail.example>", "Something else", from = "Pagingfriend <friend@paging.example>"))
    run()

    val byType = getHtml("/emails?type=${bill.id}&sender=paging.example")
    assertEquals(50, byType.select("[data-testid^=email-row-]").size, "pages of 50")
    assertEquals("52 matching emails", byType.testId("email-count").text())
    assertEquals("Page 1 of 2", byType.testId("page-number").text())
    assertEquals("2", byType.testId("active-filters").text(), "the Filters button counts the filters in use")
    assertEquals(1, byType.select("[data-testid=clear-filters]").size)
    assertTrue(byType.testId("next-page").attr("href").contains("page=2"))
    val second = getHtml("/emails" + byType.testId("next-page").attr("href").substringAfter("/emails"))
    assertEquals(2, second.select("[data-testid^=email-row-]").size)
    assertEquals(1, second.select("[data-testid=previous-page]").size)

    val bySender = getHtml("/emails?sender=PAGINGFRIEND")
    assertEquals(listOf("Something else"), bySender.select("tbody [data-col=subject]").map { it.text() })
    val unclassified = getHtml("/emails?type=none&sender=paging.example")
    assertEquals(listOf("Something else"), unclassified.select("tbody [data-col=subject]").map { it.text() })
    assertEquals(0, getHtml("/emails?response=true&sender=paging.example").select("[data-testid^=email-row-]").size)
    assertEquals(53, getHtml("/emails?status=processed&from=2026-10-01&to=2026-10-31&sender=paging.example")
        .testId("email-count").text().substringBefore(" ").toInt())
    assertEquals(1, getHtml("/emails?from=2027-01-01").select("[data-testid=no-emails]").size)
  }

  @Test
  fun `the detail page shows the email, its rules and actions, with the long text collapsed`() {
    calendar("Family-and-Kids")
    llm(
        SUMMARISE,
        """{"summary": "School news", "action_required": true, "action": "Sign the form",
            "events": [{"title": "Fete", "when": "2026-11-20T10:00", "location": "Oval"}]}"""
    )
    rule(RuleType.CLASSIFICATION, "school", listOf(condition("subject", "contains", "school")), listOf(action("set_reply_needed")))
    rule(RuleType.ACTION, "events", listOf(condition("events", "has")), listOf(action("create_events", calendar = "Family-and-Kids")))
    inbox(message("<detail@mail.example>", "From school", text = "The full text of the message"))
    run()
    val id = id("detail@mail.example")

    val page = getHtml("/emails/$id")

    assertEquals("From school", page.testId("page-title").text())
    assertEquals("Sender <sender@example.test>", page.testId("email-from").text())
    assertTrue(page.testId("email-action-required").text().contains("Sign the form"))
    assertEquals("School news", page.testId("email-summary").text())
    val full = page.testId("full-message")
    assertEquals("details", full.tagName())
    assertFalse(full.hasAttr("open"), "the full text starts collapsed")
    assertEquals("The full text of the message", full.select("pre").text())
    val event = page.select("[data-testid^=event-]").first { it.tagName() == "button" }
    assertTrue(event.attr("style").contains("#50fa7b"), "coloured by its calendar: ${event.attr("style")}")
    assertEquals("Family-and-Kids", event.select("[data-testid=event-calendar]").text())
    val classification = page.testId("classification-rule")
    assertTrue(classification.text().contains("school"))
    assertEquals("set_reply_needed", classification.select("[data-testid=action-result]").attr("data-action"))
    assertEquals("Set reply needed", classification.select(".pm-result__type").text(), "actions are shown by their labels")
    val actionRule = page.testId("action-rule")
    assertEquals("done", actionRule.select("[data-action=create_events]").attr("data-status"))
  }

  @Test
  fun `the read state, folder and flags can be changed from the detail page`() {
    llm(SUMMARISE, """{"summary": "S", "response_required": true, "action_required": true, "action": "Reply"}""")
    inbox(message("<manual@mail.example>", "Manual"))
    run()
    val id = id("manual@mail.example")

    val read = htmx().formParam("read", "true").post("/emails/$id/read")
    assertEquals(200, read.statusCode)
    assertEquals("/mail/emails/$id", read.header("HX-Redirect"))
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))
    htmx().formParam("read", "false").post("/emails/$id/read")
    assertFalse(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))

    htmx().formParam("required", "false").post("/emails/$id/action-required")
    htmx().formParam("required", "false").post("/emails/$id/response-required")
    assertEquals(false, email("manual@mail.example")["action_required"])
    assertNull(email("manual@mail.example")["action_description"])
    assertEquals(false, email("manual@mail.example")["response_required"])

    val form = parseHtml(htmx().get("/emails/$id/move").asString())
    assertTrue(form.select("select[name=folder] option").map { it.attr("value") }.contains("Folders/School"))
    assertEquals(422, htmx().formParam("folder", "").post("/emails/$id/move").statusCode)
    val moved = htmx().formParam("folder", "Folders/School").post("/emails/$id/move")
    assertEquals(200, moved.statusCode)
    assertEquals(listOf("Manual"), TestMessages.folderContents(greenMail, "Folders/School").map { it.first })
    assertEquals("Folders/School", email("manual@mail.example")["folder"])
    val missing = htmx().formParam("folder", "No/Such").post("/emails/$id/move")
    assertEquals(502, missing.statusCode)
    assertEquals("error", missing.toasts().single().first)

    val sources = actionResults("manual@mail.example").filter { it["rule_name"] == null }.map { it["action_type"] }
    assertEquals(listOf("mark_read", "mark_unread", "set_action_required", "set_response_required", "move", "move"), sources)
    val page = getHtml("/emails/$id")
    assertEquals(6, page.testId("manual-actions").select("[data-testid=action-result]").size)
    assertEquals(1, page.testId("email-errors").select("li").size, "the failed move is an error")
  }

  @Test
  fun `setting the type to Bill clears action required`() {
    llm(SUMMARISE, """{"summary": "S", "action_required": true, "action": "Pay it"}""")
    inbox(message("<typed@mail.example>", "Typed"))
    run()
    val id = id("typed@mail.example")

    htmx().formParam("typeId", types.findBill()!!.id.toString()).post("/emails/$id/type")

    assertEquals("Bill", email("typed@mail.example")["type_name"])
    assertEquals(false, email("typed@mail.example")["action_required"])
    val refused = htmx().formParam("required", "true").post("/emails/$id/action-required")
    assertEquals(502, refused.statusCode)
  }

  @Test
  fun `deleting moves the message to the trash and removes it from the list`() {
    inbox(message("<deleted@mail.example>", "Delete me"))
    run()
    val id = id("deleted@mail.example")

    val response = htmx().delete("/emails/$id")

    assertEquals("/mail/emails", response.header("HX-Redirect"))
    assertEquals(listOf("Delete me"), TestMessages.folderContents(greenMail, "Trash").map { it.first })
    assertEquals(true, email("deleted@mail.example")["deleted"])
    assertTrue(getHtml("/emails").select("[data-testid=email-row-$id]").isEmpty())
    assertEquals(404, given().get("/emails/$id").statusCode)
    assertEquals(0, run().emailsFound, "not processed again")
  }

  @Test
  fun `an event can be moved to another calendar, or added to one`() {
    calendar("Family-and-Kids")
    calendar("Football")
    llm(
        SUMMARISE,
        """{"summary": "S", "events": [{"title": "Match", "when": "2026-11-21T09:00"}, {"title": "Training", "when": "2026-11-22"}]}"""
    )
    rule(RuleType.ACTION, "match", listOf(condition("subject", "contains", "Match")),
        listOf(action("create_events", calendar = "Family-and-Kids")))
    inbox(message("<events@mail.example>", "Match day"))
    run()
    val id = id("events@mail.example")
    val match = events("events@mail.example").first { it["title"] == "Match" }
    val football = calendars.list().single { it.name == "Football" }
    assertEquals(2, FakeTools.eventFiles("Family-and-Kids").size)

    val dialog = parseHtml(htmx().get("/emails/$id/events/${match["id"]}/calendar").asString())
    assertEquals(2, dialog.select("input[name=calendarId]").size)
    assertEquals(1, dialog.select("[data-testid=cancel-calendar]").size)
    htmx().formParam("calendarId", football.id.toString()).post("/emails/$id/events/${match["id"]}/calendar")

    assertEquals(1, FakeTools.eventFiles("Family-and-Kids").size, "Training stays")
    assertEquals(1, FakeTools.eventFiles("Football").size, "Match moved")
    assertEquals("Football", events("events@mail.example").first { it["title"] == "Match" }["calendar_name"])
  }

  @Test
  fun `downloaded attachments can be downloaded and previewed`() {
    rule(RuleType.ACTION, "save", listOf(condition("attachments", "has")), listOf(action("download_attachments")))
    inbox(message("<files@mail.example>", "Files", attachments = listOf(Attachment("bill.pdf", "application/pdf", TestMessages.PDF))))
    run()
    val id = id("files@mail.example")
    val page = getHtml("/emails/$id")
    val attachment = page.select("[data-testid^=download-attachment-]").single()
    val attachmentId = attachment.attr("data-testid").removePrefix("download-attachment-")

    val download = given().get("/emails/$id/attachments/$attachmentId")
    assertEquals(200, download.statusCode)
    assertTrue(download.header("Content-Disposition").startsWith("attachment;"))
    assertArrayEquals(TestMessages.PDF, download.asByteArray())
    val inline = given().get("/emails/$id/attachments/$attachmentId?inline=true")
    assertTrue(inline.header("Content-Disposition").startsWith("inline;"))
    assertEquals("application/pdf", inline.contentType)

    assertEquals(1, page.select("[data-testid=preview-attachment-$attachmentId]").size)
    val preview = parseHtml(htmx().get("/emails/$id/attachments/$attachmentId/preview").asString())
    assertEquals("/mail/emails/$id/attachments/$attachmentId?inline=true", preview.select("iframe").attr("src"))
    assertEquals(404, given().get("/emails/$id/attachments/999999").statusCode)
  }
}
