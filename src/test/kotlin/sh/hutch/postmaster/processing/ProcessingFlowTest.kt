package sh.hutch.postmaster.processing

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.services.MailboxException
import sh.hutch.postmaster.services.ProcessingService
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The processing pipeline (REQ "Processing emails", "Using LLM to clarify any
 * details"; CLAR Q12, Q13 revision, Q14, Q15, D1, R1).
 */
@QuarkusTest
class ProcessingFlowTest : PipelineTest() {
  private fun run() = processing.runNow(RunTrigger.MANUAL)!!

  // Fetching (CLAR Q14)

  @Test
  fun `new mail is processed newest first and only once`() {
    inbox(
        message("<flow-a@mail.example>", "Older", sent = Instant.parse("2026-10-18T09:00:00Z")),
        message("<flow-b@mail.example>", "Newer", sent = Instant.parse("2026-10-19T09:00:00Z"))
    )

    val first = run()

    assertEquals("succeeded", first.status.code)
    assertEquals(2, first.emailsFound)
    assertEquals(2, first.emailsProcessed)
    val created = dataSource.rows(
        "SELECT message_id FROM Emails WHERE message_id IN ('flow-a@mail.example', 'flow-b@mail.example') ORDER BY id"
    ).map { it["message_id"] }
    assertEquals(listOf("flow-b@mail.example", "flow-a@mail.example"), created, "newest first")
    assertEquals(0, run().emailsFound, "nothing new on the next run")
  }

  @Test
  fun `older mail that wasn't processed yet is still picked up`() {
    // CLAR Q14: not "stop at the first processed email"
    inbox(message("<flow-new@mail.example>", "New", sent = Instant.parse("2026-10-20T09:00:00Z")))
    run()
    inbox(message("<flow-late@mail.example>", "Arrived late, dated earlier", sent = Instant.parse("2026-10-01T09:00:00Z")))

    assertEquals(1, run().emailsFound)
    assertEquals("processed", email("flow-late@mail.example")["status"])
  }

  @Test
  fun `mail older than the lookback window is left alone`() {
    settings.update("processing.lookback", "7d")
    inbox(message("<flow-ancient@mail.example>", "Ancient", sent = Instant.now().minusSeconds(30L * 86400)))
    inbox(message("<flow-recent@mail.example>", "Recent", sent = Instant.now().minusSeconds(3600)))

    run()

    assertEquals(0L, (dataSource.row("SELECT COUNT(*) AS n FROM Emails WHERE message_id = 'flow-ancient@mail.example'")["n"] as Number).toLong())
    assertEquals("processed", email("flow-recent@mail.example")["status"])
  }

  @Test
  fun `the email record holds the parsed message and is marked processed`() {
    // REQ Emails schema; CLAR D1, D7
    inbox(message("<flow-record@mail.example>", "Record me", from = "School <office@school.example>", text = "Hello"))

    run()

    val email = email("flow-record@mail.example")
    assertEquals("processed", email["status"])
    assertEquals("Record me", email["subject"])
    assertEquals("office@school.example", email["sender"])
    assertEquals("Hello", (email["message"] as String).trim())
    assertEquals("INBOX", email["folder"])
    assertNotNull(email["imap_uid"])
    assertNotNull(email["processed_at"])
    assertEquals("A summary", email["summary"])
  }

  // Classification (REQ Classifying emails 1-5)

  @Test
  fun `only the first matching classification rule applies, highest priority first`() {
    rule(RuleType.CLASSIFICATION, "Low", listOf(condition("subject", "contains", "camp")), listOf(action("set_type", typeName = "Legal")), 1)
    rule(RuleType.CLASSIFICATION, "High", listOf(condition("subject", "contains", "camp")), listOf(action("set_type", typeName = "High school")), 5)
    inbox(message("<flow-camp@mail.example>", "Year 9 camp"))

    run()

    assertEquals("High school", email("flow-camp@mail.example")["type_name"])
    assertEquals(listOf("High"), ruleMatches("flow-camp@mail.example"))
    assertEquals(0, llmCalls(CLASSIFY), "a classified email isn't sent to Classify (CLAR Q15)")
    assertEquals(1, llmCalls(SUMMARISE), "but is summarised")
  }

  @Test
  fun `disabled rules are ignored`() {
    val disabled = rule(RuleType.CLASSIFICATION, "Off", listOf(condition("subject", "contains", "camp")), listOf(action("set_type", typeName = "Legal")), 9)
    rules.setEnabled(RuleType.CLASSIFICATION, disabled.id!!, false)
    inbox(message("<flow-off@mail.example>", "camp"))

    run()

    assertNull(email("flow-off@mail.example")["type_name"])
    assertTrue(ruleMatches("flow-off@mail.example").isEmpty())
  }

  // LLM (REQ Using LLM 1; CLAR Q15, Q13 revision, R1)

  @Test
  fun `unclassified mail is classified by the LLM, then summarised`() {
    llm(CLASSIFY, "  primary school ")
    llm(
        SUMMARISE,
        """{"summary": "Excursion on Friday", "link": "https://school.example/news", "response_required": true,
            "action_required": true, "action": "Sign the form by Friday",
            "events": [{"title": "Zoo excursion", "when": "2026-11-05T09:00", "location": "Zoo"}]}"""
    )
    inbox(message("<flow-llm@mail.example>", "Excursion"))

    run()

    val email = email("flow-llm@mail.example")
    assertEquals(1, llmCalls(CLASSIFY))
    assertEquals(1, llmCalls(SUMMARISE))
    assertEquals("Primary school", email["type_name"], "matched ignoring case")
    assertEquals("Excursion on Friday", email["summary"])
    assertEquals("https://school.example/news", email["link"])
    assertEquals(true, email["response_required"])
    assertEquals(true, email["action_required"])
    assertEquals("Sign the form by Friday", email["action_description"])
    assertEquals(listOf("Zoo excursion"), events("flow-llm@mail.example").map { it["title"] })
  }

  @Test
  fun `no classification and an unknown answer leave the email unclassified`() {
    inbox(message("<flow-none@mail.example>", "Nothing"))
    run()
    assertNull(email("flow-none@mail.example")["type_name"])
    assertTrue(errors("flow-none@mail.example").isEmpty())

    llm(CLASSIFY, "Spaceship")
    inbox(message("<flow-unknown@mail.example>", "Unknown"))
    run()
    assertNull(email("flow-unknown@mail.example")["type_name"])
    assertEquals(listOf("llm"), errors("flow-unknown@mail.example").map { it["phase"] })
  }

  @Test
  fun `a bill never needs action, whatever the LLM says`() {
    // CLAR Q13 revision
    llm(CLASSIFY, "Bill")
    llm(SUMMARISE, """{"summary": "A bill", "action_required": true, "action": "Pay it"}""")
    inbox(message("<flow-bill@mail.example>", "Your bill"))

    run()

    val email = email("flow-bill@mail.example")
    assertEquals("Bill", email["type_name"])
    assertEquals(false, email["action_required"])
    assertNull(email["action_description"])
  }

  @Test
  fun `an answer the LLM gets wrong is recorded and processing carries on`() {
    llm(SUMMARISE, "not json at all")
    rule(RuleType.ACTION, "Read all", listOf(condition("subject", "contains", "carry")), listOf(action("mark_read")))
    inbox(message("<flow-llmwrong@mail.example>", "carry on"))

    run()

    assertEquals("processed", email("flow-llmwrong@mail.example")["status"])
    assertTrue(errors("flow-llmwrong@mail.example").any { it["phase"] == "llm" })
    assertEquals(true, email("flow-llmwrong@mail.example")["is_read"], "the action phase still ran")
  }

  @Test
  fun `while the LLM can't be reached the email waits, and the next run finishes it`() {
    // CLAR R6: actions don't run on a half-processed email
    llm(SUMMARISE, "busy", status = 503)
    rule(RuleType.ACTION, "Read all", listOf(condition("subject", "contains", "wait")), listOf(action("mark_read")))
    inbox(message("<flow-llmdown@mail.example>", "wait for it"))

    run()

    val waiting = email("flow-llmdown@mail.example")
    assertEquals("pending", waiting["status"])
    assertEquals("classified", waiting["progress"])
    assertEquals(false, waiting["is_read"], "no action ran")
    assertTrue(errors("flow-llmdown@mail.example").isEmpty())

    llm(SUMMARISE, """{"summary": "Back again"}""")
    run()

    val done = email("flow-llmdown@mail.example")
    assertEquals("processed", done["status"])
    assertNull(done["progress"])
    assertEquals("Back again", done["summary"])
    assertEquals(listOf("done"), actionResults("flow-llmdown@mail.example").map { it["status"] })
  }

  @Test
  fun `a busy LLM is asked again straight away`() {
    // The first request is refused; the retry gets the answer
    llm(SUMMARISE, """{"summary": "Second time lucky"}""")
    wireMock.stubFor(
        post(urlEqualTo("/v1/chat/completions")).withRequestBody(containing(SUMMARISE))
            .inScenario("busy").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("free")
            .willReturn(aResponse().withStatus(503))
    )
    inbox(message("<flow-llmbusy@mail.example>", "Busy"))

    run()

    assertEquals("Second time lucky", email("flow-llmbusy@mail.example")["summary"])
    assertEquals("processed", email("flow-llmbusy@mail.example")["status"])
  }

  @Test
  fun `once the attempts run out the email is processed without the LLM`() {
    llm(SUMMARISE, "down", status = 503)
    rule(RuleType.ACTION, "Read all", listOf(condition("subject", "contains", "giving")), listOf(action("mark_read")))
    inbox(message("<flow-llmgone@mail.example>", "giving up"))

    repeat(ProcessingService.MAX_ATTEMPTS - 1) { run() }
    assertEquals("pending", email("flow-llmgone@mail.example")["status"])
    run()

    val email = email("flow-llmgone@mail.example")
    assertEquals("processed", email["status"])
    assertEquals(ProcessingService.MAX_ATTEMPTS, (email["attempts"] as Number).toInt())
    assertTrue(errors("flow-llmgone@mail.example").any { it["phase"] == "llm" && (it["message"] as String).contains("503") })
    assertEquals(true, email["is_read"])
  }

  // Interruptions (CLAR R6)

  @Test
  fun `an interrupted email carries on from its last saved step, without repeating finished actions`() {
    rule(
        RuleType.ACTION, "Read and file", listOf(condition("subject", "contains", "Interrupted")),
        listOf(action("mark_read"), action("move", index = 1, destination = "Folders/School"))
    )
    inbox(message("<flow-resume@mail.example>", "Interrupted"))
    run()
    // As if the application stopped after the message was moved but before that was saved
    val id = email("flow-resume@mail.example")["id"]
    dataSource.update("DELETE FROM Email_action_results WHERE email_id = ? AND action_type = 'move'", id)
    dataSource.update(
        "UPDATE Emails SET status = 'pending', progress = 'matched', processed_at = NULL, folder = 'INBOX', moved_to = NULL WHERE id = ?", id
    )

    run()

    val email = email("flow-resume@mail.example")
    assertEquals("processed", email["status"])
    assertEquals("Folders/School", email["folder"], "the move is found to have happened")
    assertEquals(listOf("mark_read" to "done", "move" to "done"), actionResults("flow-resume@mail.example").map { it["action_type"] to it["status"] })
    assertEquals(listOf("Interrupted"), TestMessages.folderContents(greenMail, "Folders/School").map { it.first })
  }

  @Test
  fun `a forward that was interrupted isn't sent again`() {
    rule(RuleType.ACTION, "Forward", listOf(condition("subject", "contains", "Forward once")), listOf(action("forward_email", destination = "once@mail.example")))
    inbox(message("<flow-forward-once@mail.example>", "Forward once"))
    run()
    val sent = greenMail.receivedMessages.count { it.subject == "Fwd: Forward once" }
    // As if the application stopped while it was being sent
    val id = email("flow-forward-once@mail.example")["id"]
    dataSource.update("UPDATE Email_action_results SET status = 'started', detail = NULL WHERE email_id = ?", id)
    dataSource.update("UPDATE Emails SET status = 'pending', progress = 'matched' WHERE id = ?", id)

    run()

    assertEquals(sent, greenMail.receivedMessages.count { it.subject == "Fwd: Forward once" }, "not sent twice")
    val result = actionResults("flow-forward-once@mail.example").single()
    assertEquals("failed", result["status"])
    assertTrue((result["detail"] as String).contains("interrupted"))
    assertTrue(errors("flow-forward-once@mail.example").any { (it["message"] as String).startsWith("forward_email:") })
    assertEquals("processed", email("flow-forward-once@mail.example")["status"])
  }

  @Test
  fun `a new email is saved before anything is done with it`() {
    rule(RuleType.ACTION, "Forward", listOf(condition("subject", "contains", "Saved first")), listOf(action("forward_email", destination = "first@mail.example")))
    inbox(message("<flow-saved-first@mail.example>", "Saved first"))

    run()

    val email = email("flow-saved-first@mail.example")
    assertEquals("processed", email["status"])
    assertEquals(1, (email["attempts"] as Number).toInt())
    assertNull(email["progress"], "progress is cleared once processing finishes")
  }

  @Test
  fun `an email that keeps failing is given up on`() {
    inbox(message("<flow-give-up@mail.example>", "Give up"))
    run()
    val id = email("flow-give-up@mail.example")["id"]
    dataSource.update("UPDATE Emails SET status = 'pending', progress = 'recorded', attempts = ? WHERE id = ?", ProcessingService.MAX_ATTEMPTS, id)

    run()

    assertEquals("failed", email("flow-give-up@mail.example")["status"])
    assertTrue(errors("flow-give-up@mail.example").any { (it["message"] as String).contains("given up") })
  }

  @Test
  fun `the lookback counts back from the latest successful run`() {
    // Email that arrived while Postmaster was down is still found
    settings.update("processing.lookback", "1d")
    try {
      run()
      dataSource.update(
          "UPDATE Processing_runs SET started_at = ? WHERE status = 'succeeded'",
          LocalDateTime.ofInstant(Instant.now().minusSeconds(4L * 86400), ZoneOffset.UTC)
      )
      inbox(message("<flow-while-down@mail.example>", "While down", sent = Instant.now().minusSeconds(3L * 86400)))

      run()

      assertEquals("processed", email("flow-while-down@mail.example")["status"])
    } finally {
      settings.update("processing.lookback", "3650d")
    }
  }

  // Action rules (CLAR Q12)

  @Test
  fun `every matching action rule applies, with moves last and conflicts resolved by priority`() {
    rule(RuleType.ACTION, "File school", listOf(condition("subject", "contains", "term")), listOf(action("move", destination = "Folders/School")), 9)
    rule(
        RuleType.ACTION, "File bills",
        listOf(condition("subject", "contains", "term")),
        listOf(action("move", 0, destination = "Folders/Bills"), action("mark_read", 1)),
        1
    )
    inbox(message("<flow-conflict@mail.example>", "End of term"))

    run()

    val email = email("flow-conflict@mail.example")
    assertEquals(listOf("File school", "File bills"), ruleMatches("flow-conflict@mail.example"))
    assertEquals("Folders/School", email["moved_to"])
    assertEquals(true, email["is_read"])
    assertEquals(listOf("End of term"), TestMessages.folderContents(greenMail, "Folders/School").map { it.first })
    assertTrue(TestMessages.folderContents(greenMail, "Folders/Bills").isEmpty())
    val results = actionResults("flow-conflict@mail.example")
    val skipped = results.single { it["action_type"] == "move" && it["status"] == "skipped" }
    assertEquals("File bills", skipped["rule_name"])
    assertTrue((skipped["detail"] as String).startsWith("Conflicts with move from rule File school"), skipped["detail"].toString())
    assertTrue(errors("flow-conflict@mail.example").any { it["phase"] == "action" }, "the conflict is logged")
    assertTrue(results.indexOfFirst { it["action_type"] == "mark_read" } < results.indexOfFirst { it["action_type"] == "move" && it["status"] == "done" }, "read state before the move")
  }

  @Test
  fun `skip_processing stops processing but the email is still recorded`() {
    // REQ skip_processing; CLAR D1 (skipped)
    rule(
        RuleType.CLASSIFICATION, "Ignore",
        listOf(condition("subject", "starts_with", "auto")),
        listOf(action("skip_processing", 0), action("move", 1, destination = "Folders/Bills"))
    )
    rule(RuleType.ACTION, "Would read", listOf(condition("subject", "contains", "auto")), listOf(action("mark_read")))
    inbox(message("<flow-skip@mail.example>", "Automatic reply"))

    run()

    val email = email("flow-skip@mail.example")
    assertEquals("skipped", email["status"])
    assertEquals(0, llmCalls(SUMMARISE), "no LLM after skip_processing")
    assertEquals(false, email["is_read"])
    assertTrue(actionResults("flow-skip@mail.example").any { it["action_type"] == "move" && it["status"] == "skipped" })
    assertEquals(0, run().emailsFound, "a skipped email isn't processed again")
  }

  @Test
  fun `a failing action is recorded as failed with an error, and the email is still processed`() {
    rule(RuleType.ACTION, "Bad move", listOf(condition("subject", "contains", "nowhere")), listOf(action("move", destination = "Folders/Missing")))
    inbox(message("<flow-badmove@mail.example>", "Go nowhere"))

    run()

    assertEquals("processed", email("flow-badmove@mail.example")["status"])
    assertEquals("failed", actionResults("flow-badmove@mail.example").single()["status"])
    assertTrue(errors("flow-badmove@mail.example").any { it["phase"] == "action" && (it["message"] as String).startsWith("move:") })
  }

  @Test
  fun `a setting that can't be read fails its action without losing the email's other work`() {
    // A @Transactional bean throwing inside the email's transaction must not
    // roll it back after the message has already been moved
    rule(
        RuleType.ACTION, "Forward and file", listOf(condition("subject", "contains", "unreadable")),
        listOf(action("forward_email", destination = "someone@mail.example"), action("move", index = 1, destination = "Folders/School"))
    )
    settings.update("smtp.username", "postmaster")
    dataSource.update("UPDATE Settings SET setting_value = 'enc:not-encrypted' WHERE setting_key = 'smtp.password'")
    inbox(message("<flow-unreadable@mail.example>", "An unreadable password"))
    try {
      run()
    } finally {
      settings.clear("smtp.username")
      settings.clear("smtp.password")
    }

    val email = email("flow-unreadable@mail.example")
    assertEquals("processed", email["status"])
    assertEquals("Folders/School", email["folder"])
    val results = actionResults("flow-unreadable@mail.example").associate { it["action_type"] to it["status"] }
    assertEquals(mapOf("forward_email" to "failed", "move" to "done"), results)
    assertTrue(errors("flow-unreadable@mail.example").any { (it["message"] as String).startsWith("forward_email:") })
  }

  // Reprocessing

  @Test
  fun `an email can be reprocessed after the rules change`() {
    inbox(message("<flow-again@mail.example>", "Payslip October"))
    run()
    assertNull(email("flow-again@mail.example")["type_name"])

    rule(RuleType.CLASSIFICATION, "Payslips", listOf(condition("subject", "starts_with", "payslip")), listOf(action("set_type", typeName = "Payslip")))
    processing.reprocess((email("flow-again@mail.example")["id"] as Number).toLong())

    assertEquals("Payslip", email("flow-again@mail.example")["type_name"])
    assertEquals(listOf("Payslips"), ruleMatches("flow-again@mail.example"))
    assertEquals(1L, (dataSource.row("SELECT COUNT(*) AS n FROM Emails WHERE message_id = 'flow-again@mail.example'")["n"] as Number).toLong())
  }

  @Test
  fun `reprocessing finds events again, keeping those already added without duplicating them`() {
    llm(
        SUMMARISE,
        """{"summary": "Two events", "events": [{"title": "Fete", "when": "2026-11-20T10:00"}, {"title": "Disco", "when": "2026-11-27T18:00"}]}"""
    )
    inbox(message("<flow-events-again@mail.example>", "Two events"))
    run()
    dataSource.update(
        "UPDATE Email_events SET is_added = TRUE, khal_uid = 'fete' WHERE title = 'Fete' AND email_id = ?",
        email("flow-events-again@mail.example")["id"]
    )

    processing.reprocess((email("flow-events-again@mail.example")["id"] as Number).toLong())

    val events = events("flow-events-again@mail.example").associate { it["title"] to it["is_added"] }
    assertEquals(mapOf("Fete" to true, "Disco" to false), events, "the pending event comes back and the added one isn't doubled")
  }

  @Test
  fun `an email is reprocessed by one request at a time`() {
    inbox(message("<flow-twice@mail.example>", "Twice"))
    run()
    val id = (email("flow-twice@mail.example")["id"] as Number).toLong()
    llm(SUMMARISE, """{"summary": "slow"}""", delayMs = 3000)

    // Requests run on Quarkus threads, which have the application's class loader; so must this one
    val loader = Thread.currentThread().contextClassLoader
    val first = CompletableFuture.supplyAsync {
      Thread.currentThread().contextClassLoader = loader
      processing.reprocess(id)
    }
    Thread.sleep(800)

    assertThrows<IllegalStateException> { processing.reprocess(id) }
    first.get(30, TimeUnit.SECONDS)
    processing.reprocess(id)
  }

  @Test
  fun `reprocessing a message that has gone from the mailbox is an error`() {
    inbox(message("<flow-gone@mail.example>", "Gone"))
    run()
    greenMail.purgeEmailFromAllMailboxes()

    assertThrows<MailboxException> { processing.reprocess((email("flow-gone@mail.example")["id"] as Number).toLong()) }
  }

  // Runs (PLAN 5.5, 5.6)

  @Test
  fun `only one run happens at a time`() {
    llm(SUMMARISE, """{"summary": "slow"}""", delayMs = 3000)
    inbox(message("<flow-slow@mail.example>", "Slow"))

    val first = CompletableFuture.supplyAsync { processing.runNow(RunTrigger.MANUAL) }
    Thread.sleep(800)

    assertNull(processing.runNow(RunTrigger.MANUAL), "a second run is refused")
    assertFalse(processing.runNowAsync())
    assertEquals("succeeded", first.get(30, TimeUnit.SECONDS)!!.status.code)
  }

  @Test
  fun `run now starts a run in the background`() {
    inbox(message("<flow-async@mail.example>", "Async"))

    assertTrue(processing.runNowAsync())

    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
      val status = dataSource.rows("SELECT status FROM Emails WHERE message_id = 'flow-async@mail.example'").firstOrNull()?.get("status")
      if (status == "processed") break
      Thread.sleep(200)
    }
    assertEquals("processed", email("flow-async@mail.example")["status"])
    val latest = dataSource.rows("SELECT trigger_source, status FROM Processing_runs ORDER BY id DESC").first()
    assertEquals("manual", latest["trigger_source"])
  }

  @Test
  fun `a run that can't reach the mailbox is recorded as failed`() {
    settings.update("imap.port", "1")

    val run = processing.runNow(RunTrigger.MANUAL)!!

    assertEquals("failed", run.status.code)
    assertTrue(!run.error.isNullOrBlank())
  }
}
