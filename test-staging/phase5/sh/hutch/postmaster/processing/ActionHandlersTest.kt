package sh.hutch.postmaster.processing

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.junit.QuarkusTest
import jakarta.mail.Flags
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.Attachment
import sh.hutch.postmaster.testsupport.TestMessages.message
import java.nio.file.Files
import java.time.Instant

/** Each action does what REQ "Action implementations" says (CLAR Q6, Q7, Q11, D6, D8, D11, D15, D18) */
@QuarkusTest
class ActionHandlersTest : PipelineTest() {
  private fun run() = processing.runNow(RunTrigger.MANUAL)!!

  /** A select_calendar answer putting every event id the test could have created in [calendar] */
  private fun everyEventIn(calendar: String) =
      (1..2000).joinToString(", ", "{", "}") { "\"$it\": \"$calendar\"" }

  private fun actionRule(name: String, vararg actions: sh.hutch.postmaster.models.ActionInput) =
      rule(RuleType.ACTION, name, listOf(condition("subject", "contains", name)), actions.toList())

  @Test
  fun `set_type and set_reply_needed set the type and the response flag`() {
    rule(
        RuleType.CLASSIFICATION, "typer",
        listOf(condition("subject", "contains", "typer")),
        listOf(action("set_type", 0, typeName = "Ebay"), action("set_reply_needed", 1))
    )
    inbox(message("<h-type@mail.example>", "typer"))

    run()

    assertEquals("Ebay", email("h-type@mail.example")["type_name"])
    assertEquals(true, email("h-type@mail.example")["response_required"])
  }

  @Test
  fun `mark_read and mark_unread change the read state in the mailbox`() {
    actionRule("reader", action("mark_read"))
    inbox(message("<h-read@mail.example>", "reader"))
    run()
    assertTrue(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))
    assertEquals(true, email("h-read@mail.example")["is_read"])

    greenMail.purgeEmailFromAllMailboxes()
    actionRule("unreader", action("mark_unread"))
    val seen = message("<h-unread@mail.example>", "unreader").apply { setFlag(Flags.Flag.SEEN, true) }
    inbox(seen)
    run()
    assertFalse(TestMessages.folderContents(greenMail, "INBOX").single().second.contains(Flags.Flag.SEEN))
    assertEquals(false, email("h-unread@mail.example")["is_read"])
  }

  @Test
  fun `move files the email and records where it went`() {
    actionRule("mover", action("move", destination = "Folders/Bills"))
    inbox(message("<h-move@mail.example>", "mover"))

    run()

    assertEquals(listOf("mover"), TestMessages.folderContents(greenMail, "Folders/Bills").map { it.first })
    assertEquals("Folders/Bills", email("h-move@mail.example")["moved_to"])
    assertEquals("Folders/Bills", email("h-move@mail.example")["folder"])
  }

  @Test
  fun `delete moves the email to the trash folder and keeps the record`() {
    // REQ delete; CLAR Q6, D11
    actionRule("deleter", action("delete"))
    inbox(message("<h-delete@mail.example>", "deleter"))

    run()

    assertEquals(listOf("deleter"), TestMessages.folderContents(greenMail, "Trash").map { it.first })
    assertEquals("Trash", email("h-delete@mail.example")["moved_to"])
    assertEquals("processed", email("h-delete@mail.example")["status"])
  }

  @Test
  fun `forward_email sends the email on`() {
    // REQ forward_email; CLAR Q7
    actionRule("forwarder", action("forward_email", destination = "accountant@example.test"))
    inbox(message("<h-forward@mail.example>", "forwarder"))

    run()

    assertTrue(greenMail.waitForIncomingEmail(5000, 1))
    assertTrue(greenMail.receivedMessages.any { it.subject == "Fwd: forwarder" })
  }

  @Test
  fun `download_attachments saves the wanted attachments where the UI can find them`() {
    // REQ download_attachments: "non-filtered out attachments to the configured location"; CLAR D9
    actionRule("downloader", action("download_attachments"))
    inbox(
        message(
            "<h-download@mail.example>", "downloader",
            attachments = listOf(
                Attachment("bill.pdf", "application/pdf", TestMessages.PDF),
                Attachment("notes.txt", "text/plain", "ignored".toByteArray())
            ),
            sent = Instant.parse("2026-10-20T09:00:00Z")
        )
    )

    run()

    val id = (email("h-download@mail.example")["id"] as Number).toLong()
    val rows = dataSource.rows("SELECT filename, stored_path, downloaded_at FROM Email_attachments WHERE email_id = ?", id)
    assertEquals(listOf("bill.pdf"), rows.map { it["filename"] }, "only extensions in attachments.extensions")
    val stored = rows.single()["stored_path"] as String
    assertEquals("2026/10/$id/bill.pdf", stored)
    assertArrayEquals(TestMessages.PDF, Files.readAllBytes(attachments.resolve(stored)))
    assertTrue(rows.single()["downloaded_at"] != null)
  }

  @Test
  fun `fetch_link_contents fetches the email's link and stores its text and summary`() {
    // REQ fetch_link_contents; REQ Using LLM 2
    wireMock.stubFor(get(urlEqualTo("/newsletter")).willReturn(aResponse().withBody("<html><body><h1>Newsletter</h1><p>Term 4 news</p></body></html>")))
    llm(SUMMARISE, """{"summary": "School news", "link": "${wireMock.baseUrl()}/newsletter"}""")
    llm(SUMMARISE_PAGE, "Term 4 has started")
    rule(RuleType.CLASSIFICATION, "linker", listOf(condition("subject", "contains", "linker")), listOf(action("fetch_link_contents")))
    inbox(message("<h-link@mail.example>", "linker"))

    run()

    val email = email("h-link@mail.example")
    assertTrue((email["link_contents"] as String).contains("Term 4 news"))
    assertFalse((email["link_contents"] as String).contains("<p>"))
    assertEquals("Term 4 has started", email["link_summary"])
    assertEquals(1, FakeTools.chromiumCalls().size)
  }

  @Test
  fun `fetch_link_contents without a link is skipped`() {
    rule(RuleType.CLASSIFICATION, "nolink", listOf(condition("subject", "contains", "nolink")), listOf(action("fetch_link_contents")))
    inbox(message("<h-nolink@mail.example>", "nolink"))

    run()

    assertEquals("skipped", actionResults("h-nolink@mail.example").single()["status"])
  }

  @Test
  fun `check_for_events finds events and suggests calendars without adding them`() {
    // CLAR Q11
    calendar("Family-and-Kids")
    llm(IDENTIFY_EVENTS, """{"events": [{"title": "Concert", "when": "2026-11-20T18:30", "location": "Hall"}]}""")
    llm(SELECT_CALENDAR, everyEventIn("Family-and-Kids"))
    rule(RuleType.CLASSIFICATION, "checker", listOf(condition("subject", "contains", "checker")), listOf(action("check_for_events")))
    inbox(message("<h-check@mail.example>", "checker"))

    run()

    val event = events("h-check@mail.example").single()
    assertEquals("Concert", event["title"])
    assertEquals(false, event["is_added"])
    assertTrue(FakeTools.khalCalls().isEmpty(), "nothing added to a calendar")
  }

  @Test
  fun `create_events adds the events to the action's calendar with its notifications`() {
    // REQ create_events (notifications); CLAR D6, D8, D18
    calendar("Kids medical", periods = "1d")
    llm(
        SUMMARISE,
        """{"summary": "Appointments", "events": [
            {"title": "Dentist", "when": "2026-11-03T15:30", "location": "Clinic"},
            {"title": "School photos", "when": "2026-11-10"}]}"""
    )
    actionRule("creator", action("create_events", calendar = "Kids medical", notifications = "2h, 30m"))
    inbox(message("<h-create@mail.example>", "creator"))

    run()

    val events = events("h-create@mail.example")
    assertTrue(events.all { it["is_added"] == true && it["calendar_name"] == "Kids medical" && it["khal_uid"] != null })
    assertEquals(false, events.single { it["title"] == "Dentist" }["all_day"])
    assertEquals(true, events.single { it["title"] == "School photos" }["all_day"], "a date with no time is all day (CLAR D8)")
    val imports = FakeTools.khalCalls().filter { "import" in it.args }
    assertEquals(2, imports.size)
    val dentist = imports.single { it.importedIcs!!.contains("SUMMARY:Dentist") }.importedIcs!!
    assertTrue(dentist.contains("TRIGGER:-PT2H") && dentist.contains("TRIGGER:-PT30M"), "the action's notifications win: $dentist")
    assertTrue(dentist.contains("DTEND:20261103T053000Z"), "default duration 1h, in UTC: $dentist")
    assertEquals(listOf("Kids medical", "Kids medical"), imports.map { it.args[it.args.indexOf("-a") + 1] })
  }

  @Test
  fun `create_events uses the calendar's notifications when the action has none, and asks the LLM for a calendar`() {
    // CLAR D6, Q15
    calendar("Football", periods = "1d")
    llm(SUMMARISE, """{"summary": "Game", "events": [{"title": "Grand final", "when": "2026-09-26T14:00"}]}""")
    llm(SELECT_CALENDAR, everyEventIn("Football"))
    actionRule("picker", action("create_events"))
    inbox(message("<h-pick@mail.example>", "picker"))

    run()

    val event = events("h-pick@mail.example").single()
    assertEquals("Football", event["calendar_name"])
    val ics = FakeTools.khalCalls().single { "import" in it.args }.importedIcs!!
    assertTrue(ics.contains("TRIGGER:-P1D") || ics.contains("TRIGGER:-PT24H"), ics)
  }

  @Test
  fun `events the LLM can't place in a calendar aren't added`() {
    llm(SUMMARISE, """{"summary": "x", "events": [{"title": "Mystery", "when": "2026-12-01T10:00"}]}""")
    actionRule("orphan", action("create_events"))
    inbox(message("<h-orphan@mail.example>", "orphan"))

    run()

    assertEquals(false, events("h-orphan@mail.example").single()["is_added"])
    assertTrue(FakeTools.khalCalls().none { "import" in it.args })
  }

  @Test
  fun `an event time the LLM got wrong is an error and the event is left out`() {
    calendar("Bills")
    llm(SUMMARISE, """{"summary": "x", "events": [{"title": "Soon", "when": "next Tuesday"}]}""")
    actionRule("badtime", action("create_events", calendar = "Bills"))
    inbox(message("<h-badtime@mail.example>", "badtime"))

    run()

    assertTrue(events("h-badtime@mail.example").isEmpty())
    assertTrue(errors("h-badtime@mail.example").any { it["phase"] == "llm" })
    assertNull(FakeTools.khalCalls().firstOrNull { "import" in it.args })
  }
}
