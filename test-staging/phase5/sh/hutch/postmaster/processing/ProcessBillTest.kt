package sh.hutch.postmaster.processing

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.TestMessages
import sh.hutch.postmaster.testsupport.TestMessages.Attachment
import sh.hutch.postmaster.testsupport.TestMessages.message
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * REQ "process bill": the biller, amount, due date, pay link and bill are
 * found with regexes and the DOM, the LLM fills what's missing, a reminder
 * goes in the calendar the day before, and the bill is downloaded. CLAR D14
 * (amounts with thousands separators or no cents; dd/MM/yyyy dates;
 * configurable anchor texts), Q16 (the action's calendar).
 */
@QuarkusTest
class ProcessBillTest : PipelineTest() {
  @BeforeEach
  fun billRules() {
    calendar("Bills")
    rule(RuleType.CLASSIFICATION, "Bills", listOf(condition("subject", "contains", "invoice")), listOf(action("set_type", typeName = "Bill")))
    rule(RuleType.ACTION, "Pay bills", listOf(condition("type", "is", "Bill")), listOf(action("process_bill", calendar = "Bills")))
  }

  private fun bill(text: String, html: String? = null, attachments: List<Attachment> = emptyList()): String {
    val id = "bill-${counter.incrementAndGet()}@mail.example"
    inbox(message("<$id>", "Your invoice", from = "Electric Light Company <billing@electric.example>", text = text, html = html, attachments = attachments))
    processing.runNow(RunTrigger.MANUAL)
    return id
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = [
        "Amount due: \$123.45. Due by 25 Nov 2026 | 123.45",
        "Total due \$1,234.56 - pay by 25 Nov 2026 | 1234.56",
        "The amount due is \$120 and the due date is 25 Nov 2026 | 120",
        "Amount due \$ 99.5, due by 25 Nov 2026 | 99.5",
        "Last bill was \$50.00 and was paid. Amount due: \$80.10. Due by 25 Nov 2026 | 80.10"
      ]
  )
  fun `the amount is found with or without cents and thousands separators`(text: String, amount: String) {
    val id = bill(text)

    assertEquals(0, BigDecimal(amount).compareTo(email(id)["bill_amount"] as BigDecimal), "amount in: $text")
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = [
        "Amount due \$10.00. Due by 25 Nov 2026 | 2026-11-25",
        "Amount due \$10.00. Pay by 5 November 2026 | 2026-11-05",
        "Amount due \$10.00. Pay by 05 nov 2026 | 2026-11-05",
        "Amount due \$10.00, due date: 1st December 2026 | 2026-12-01",
        "Amount due \$10.00. DUE BY 3 MAY 2027 | 2027-05-03",
        "Amount due \$10.00. Please pay by the due date, which is 25/11/2026 | 2026-11-25",
        "Amount due \$10.00. Pay by 7/1/2027 | 2027-01-07"
      ]
  )
  fun `the due date is found in the usual formats`(text: String, due: String) {
    val id = bill(text)

    assertEquals(LocalDate.parse(due), (email(id)["bill_due_date"] as java.sql.Date).toLocalDate(), "due date in: $text")
  }

  @Test
  fun `a reminder is added the day before the due date in the action's calendar`() {
    val id = bill(
        "Amount due \$123.45. Due by 25 Nov 2026.",
        html = "<p>Amount due \$123.45. Due by 25 Nov 2026.</p><a href=\"https://pay.electric.example/abc\">Pay now</a>"
    )

    val email = email(id)
    assertEquals("Electric Light Company", email["bill_sender"])
    assertEquals("https://pay.electric.example/abc", email["bill_pay_link"])
    val reminder = events(id).single()
    assertEquals("Pay Electric Light Company \$123.45", reminder["title"])
    assertEquals(true, reminder["all_day"])
    assertEquals(LocalDateTime.parse("2026-11-24T00:00"), (reminder["starts_at"] as java.sql.Timestamp).toLocalDateTime())
    assertEquals("Bills", reminder["calendar_name"])
    assertEquals(true, reminder["is_added"])
    val notes = reminder["notes"] as String
    for (line in listOf("Biller: Electric Light Company", "Amount: \$123.45", "Due: 2026-11-25", "Pay: https://pay.electric.example/abc")) {
      assertTrue(notes.contains(line), "notes have '$line': $notes")
    }
    val import = FakeTools.khalCalls().single { "import" in it.args }
    assertEquals("Bills", import.args[import.args.indexOf("-a") + 1])
    assertTrue(import.importedIcs!!.contains("DTSTART;VALUE=DATE:20261124"))
  }

  @Test
  fun `the bill link is downloaded when no PDF is attached`() {
    wireMock.stubFor(get(urlEqualTo("/bills/oct.pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(TestMessages.PDF)))
    val id = bill(
        "Amount due \$10.00. Due by 25 Nov 2026.",
        html = "<p>Due by 25 Nov 2026</p><a href=\"${wireMock.baseUrl()}/bills/oct.pdf\">View your bill</a>"
    )

    val email = email(id)
    assertEquals("${wireMock.baseUrl()}/bills/oct.pdf", email["bill_download_url"])
    val stored = dataSource.rows("SELECT filename, stored_path FROM Email_attachments WHERE email_id = ?", email["id"]).single()
    assertEquals("oct.pdf", stored["filename"])
    assertArrayEquals(TestMessages.PDF, Files.readAllBytes(attachments.resolve(stored["stored_path"] as String)))
  }

  @Test
  fun `an attached PDF is saved as the bill`() {
    val id = bill("Amount due \$10.00. Due by 25 Nov 2026.", attachments = listOf(Attachment("statement.pdf", "application/pdf", TestMessages.PDF)))

    val stored = dataSource.rows("SELECT filename, stored_path FROM Email_attachments WHERE email_id = ?", email(id)["id"]).single()
    assertEquals("statement.pdf", stored["filename"])
    assertTrue(stored["stored_path"] != null)
  }

  @Test
  fun `the LLM fills in only the details that weren't found`() {
    // REQ process bill: "If any information is missing you can pass the HTML message body to the LLM"
    llm(
        BILL_DETAILS,
        """{"organisation": "Other Name", "due_date": "2027-01-31", "amount_due": 77.70, "payment_link": "https://pay.example/llm"}"""
    )

    val id = bill("Please pay by 25 Nov 2026.")

    val email = email(id)
    assertEquals(1, llmCalls(BILL_DETAILS))
    assertEquals(0, BigDecimal("77.70").compareTo(email["bill_amount"] as BigDecimal))
    assertEquals("https://pay.example/llm", email["bill_pay_link"])
    assertEquals(LocalDate.parse("2026-11-25"), (email["bill_due_date"] as java.sql.Date).toLocalDate(), "found in the text, kept")
    assertEquals("Electric Light Company", email["bill_sender"])
  }

  @Test
  fun `the LLM isn't asked when everything was found`() {
    bill("Amount due \$10.00. Due by 25 Nov 2026.", html = "<p>x</p><a href=\"https://pay.example/x\">Pay now</a>")

    assertEquals(0, llmCalls(BILL_DETAILS))
  }

  @Test
  fun `without a due date there's no reminder, and that's reported`() {
    val id = bill("Amount due \$10.00, whenever you like.", html = "<a href=\"https://pay.example/x\">Pay now</a>")

    assertTrue(events(id).isEmpty())
    assertTrue(errors(id).any { it["phase"] == "action" })
  }

  @Test
  fun `the biller falls back to the sender's domain`() {
    inbox(message("<bill-domain@mail.example>", "Water invoice", from = "accounts@water.example", text = "Amount due \$5.00. Due by 1 Dec 2026"))
    processing.runNow(RunTrigger.MANUAL)

    assertEquals("water.example", email("bill-domain@mail.example")["bill_sender"])
  }

  @Test
  fun `emails that aren't bills are left alone`() {
    // REQ process bill: "If the email is not classified as a bill then do nothing"
    rule(RuleType.ACTION, "Bill everything", listOf(condition("subject", "contains", "newsletter")), listOf(action("process_bill", calendar = "Bills")))
    inbox(message("<bill-not@mail.example>", "School newsletter", text = "Amount due \$10.00. Due by 25 Nov 2026"))
    processing.runNow(RunTrigger.MANUAL)

    val email = email("bill-not@mail.example")
    assertNull(email["bill_amount"])
    assertNull(email["bill_due_date"])
    assertEquals("skipped", actionResults("bill-not@mail.example").single { it["action_type"] == "process_bill" }["status"])
    assertTrue(FakeTools.khalCalls().none { "import" in it.args })
  }

  companion object {
    private val counter = AtomicInteger()
  }
}
