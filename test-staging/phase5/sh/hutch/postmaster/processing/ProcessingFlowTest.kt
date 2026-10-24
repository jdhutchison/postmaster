package sh.hutch.postmaster.processing

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
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.message
import sh.hutch.postmaster.testsupport.row
import java.time.Instant
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
  fun `LLM failures are recorded and processing carries on`() {
    llm(SUMMARISE, "broken", status = 500)
    rule(RuleType.ACTION, "Read all", listOf(condition("subject", "contains", "carry")), listOf(action("mark_read")))
    inbox(message("<flow-llmfail@mail.example>", "carry on"))

    run()

    assertEquals("processed", email("flow-llmfail@mail.example")["status"])
    assertTrue(errors("flow-llmfail@mail.example").any { it["phase"] == "llm" })
    assertEquals(true, email("flow-llmfail@mail.example")["is_read"], "the action phase still ran")
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
