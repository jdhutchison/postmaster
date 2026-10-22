package sh.hutch.postmaster.repositories

import io.agroal.api.AgroalDataSource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.domain.EmailStatus
import sh.hutch.postmaster.domain.RuleType
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.services.MessageIds
import sh.hutch.postmaster.testsupport.KnownDataProfile
import sh.hutch.postmaster.testsupport.row
import java.time.Instant

/**
 * Repository queries against the known data set (PLAN 1.3, 1.5). "Active"
 * means not soft-deleted (REQ Types 5, Editing a rule 5; CLAR D3, D11).
 */
@QuarkusTest
@TestProfile(KnownDataProfile::class)
class RepositoriesTest {
  @Inject
  lateinit var types: TypeRepository

  @Inject
  lateinit var calendars: CalendarRepository

  @Inject
  lateinit var prompts: PromptRepository

  @Inject
  lateinit var rules: RuleRepository

  @Inject
  lateinit var conditions: ConditionRepository

  @Inject
  lateinit var ruleActions: RuleActionRepository

  @Inject
  lateinit var emails: EmailRepository

  @Inject
  lateinit var events: EmailEventRepository

  @Inject
  lateinit var attachments: EmailAttachmentRepository

  @Inject
  lateinit var ruleMatches: EmailRuleMatchRepository

  @Inject
  lateinit var actionResults: EmailActionResultRepository

  @Inject
  lateinit var errors: EmailErrorRepository

  @Inject
  lateinit var runs: ProcessingRunRepository

  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var entityManager: EntityManager

  // Types (REQ Types 1, 5; CLAR Q16, D20)

  @Test
  @TestTransaction
  fun `active types leave out soft-deleted ones`() {
    val names = types.listActive().map { it.name }

    assertEquals(9, names.size, "$names")
    assertFalse("Retired type" in names)
    assertNull(types.findActiveByName("Retired type"))
  }

  @Test
  @TestTransaction
  fun `types are found by name regardless of case`() {
    assertEquals("Primary school", types.findActiveByName("PRIMARY SCHOOL")!!.name)
    assertEquals("Bill", types.findActiveByName("bill")!!.name)
  }

  @Test
  @TestTransaction
  fun `the Bill type is found by its system key`() {
    assertEquals("Bill", types.findBill()!!.name)
    assertEquals("Bill", types.findBySystemKey("bill")!!.name)
    assertEquals("bill", types.findBill()!!.systemKey)
  }

  @Test
  @TestTransaction
  fun `the Bill type is still found by key after it is renamed`() {
    // CLAR Q16: the code finds Bill by key, so it can be renamed
    val bill = types.findBill()!!
    bill.name = "Utility bill"
    entityManager.flush()

    assertEquals("Utility bill", types.findBill()!!.name)
  }

  // Calendars (REQ Calendars 1, 3; Prompt placeholder "table of enabled calendars")

  @Test
  @TestTransaction
  fun `active calendars leave out deleted ones, and enabled ones also leave out disabled ones`() {
    assertEquals(
        setOf("Family-and-Kids", "Kids medical", "Football", "Bills"),
        calendars.listActive().map { it.name }.toSet()
    )
    assertEquals(setOf("Family-and-Kids", "Kids medical", "Bills"), calendars.listEnabled().map { it.name }.toSet())
    assertNull(calendars.findActiveByName("Retired calendar"))
    assertEquals("Football", calendars.findActiveByName("Football")!!.name)
  }

  // Prompts (REQ Prompts; CLAR Q15)

  @Test
  @TestTransaction
  fun `prompts are found by key, and deleted prompts are left out`() {
    assertEquals("Summarising the email", prompts.findByKey("summarise")!!.name)
    assertEquals("Identifying events", prompts.findByKey("identify_events")!!.name)
    val names = prompts.listActive().map { it.name }
    assertTrue("Translate to English" in names)
    assertFalse("Retired prompt" in names)
    assertEquals(7, names.size)
  }

  // Rules (REQ Classifying emails 1; CLAR D5 ties go to the older rule)

  @Test
  @TestTransaction
  fun `enabled rules come highest priority first, ties oldest first`() {
    assertEquals(
        listOf("School newsletters", "Bills by subject", "Payslips"),
        rules.listEnabled(RuleType.CLASSIFICATION).map { it.name }
    )
    assertEquals(
        listOf("Pay bills", "School events", "Forward payslips"),
        rules.listEnabled(RuleType.ACTION).map { it.name }
    )
  }

  @Test
  @TestTransaction
  fun `active rules include disabled ones but not deleted ones`() {
    // REQ Rules 3 and 6: the list shows every rule, with a toggle for enabled
    assertEquals(
        listOf("Disabled classification", "School newsletters", "Bills by subject", "Payslips"),
        rules.listActive(RuleType.CLASSIFICATION).map { it.name }
    )
    assertEquals(
        listOf("Disabled action", "Pay bills", "School events", "Forward payslips"),
        rules.listActive(RuleType.ACTION).map { it.name }
    )
  }

  @Test
  @TestTransaction
  fun `rules are found by name within their type, regardless of case`() {
    // CLAR D20: rule names are unique within a rule type, ignoring case
    assertEquals("Pay bills", rules.findActiveByName(RuleType.ACTION, "PAY BILLS")!!.name)
    assertNull(rules.findActiveByName(RuleType.CLASSIFICATION, "Pay bills"))
    assertNull(rules.findActiveByName(RuleType.CLASSIFICATION, "Retired classification"))
  }

  @Test
  @TestTransaction
  fun `a rule's conditions and actions are in position order`() {
    val rule = rules.findActiveByName(RuleType.ACTION, "Pay bills")!!

    assertEquals(listOf("type"), conditions.listForRule(rule.id!!).map { it.attribute.code })
    assertEquals(
        listOf("process_bill", "move", "mark_read"),
        ruleActions.listActiveForRule(rule.id!!).map { it.actionType },
        "the deleted delete action is left out"
    )
    assertEquals(
        listOf("process_bill", "move"),
        ruleActions.listEnabledForRule(rule.id!!).map { it.actionType },
        "the disabled mark_read action is left out too"
    )
    val school = rules.findActiveByName(RuleType.CLASSIFICATION, "School newsletters")!!
    assertEquals(listOf("sender", "subject"), school.conditions.map { it.attribute.code })
    assertEquals(listOf("set_type", "check_for_events"), school.actions.map { it.actionType })
  }

  @Test
  @TestTransaction
  fun `a rule action refers to its type or calendar`() {
    // REQ Creating a new rule 11 (set_type needs a type); CLAR D6 and Q16
    // (create_events and process_bill take a calendar)
    val newsletters = rules.findActiveByName(RuleType.CLASSIFICATION, "School newsletters")!!
    assertEquals("Primary school", ruleActions.listActiveForRule(newsletters.id!!).first().type!!.name)

    val payBills = rules.findActiveByName(RuleType.ACTION, "Pay bills")!!
    val processBill = ruleActions.listActiveForRule(payBills.id!!).first()
    assertEquals("Bills", processBill.calendar!!.name)

    val events = rules.findActiveByName(RuleType.ACTION, "School events")!!
    val createEvents = ruleActions.listActiveForRule(events.id!!).first()
    assertEquals("Family-and-Kids", createEvents.calendar!!.name)
    assertEquals("1d", createEvents.notifications)
  }

  // Emails (REQ Viewing emails 1; CLAR D7, D11, Q14)

  @Test
  @TestTransaction
  fun `recent emails are the newest active ones`() {
    val recent = emails.listRecent(30)

    assertEquals(30, recent.size)
    assertEquals("Still processing", recent.first().subject)
    assertEquals(recent.sortedByDescending { it.receivedAt }, recent)
    assertTrue(recent.none { it.subject == "Deleted email" })
  }

  @Test
  @TestTransaction
  fun `a deleted email still counts as seen so it isn't processed again`() {
    // CLAR D11 and Q14 (processed email recognised by Message-ID)
    assertTrue(emails.existsByMessageId("deleted-1@example.test"))
    assertTrue(emails.existsByMessageId("bill-1@electriclight.example"))
    assertFalse(emails.existsByMessageId("never-seen@example.test"))
    val deleted = emails.findByMessageId("deleted-1@example.test")!!
    assertNull(emails.findActive(deleted.id!!))
  }

  @Test
  @TestTransaction
  fun `a maximum-length Message-ID is stored whole and recognised as already seen`() {
    // Coordinator request; CLAR Q14 and D7 (recognised by Message-ID), D20 (unique on its hash)
    val id = MessageIds.normalise("<" + "m".repeat(985) + "@example.test>")!!
    assertEquals(998, id.length)
    emails.persist(newEmail(id))
    entityManager.flush()

    assertTrue(emails.existsByMessageId(id))
    assertEquals(id, emails.findByMessageId(id)!!.messageId)
    assertThrows<PersistenceException> {
      emails.persist(newEmail(id))
      entityManager.flush()
    }
  }

  @Test
  @TestTransaction
  fun `an email's events, attachments, matched rules, action results and errors are listed`() {
    val excursion = emails.findByMessageId("excursion-1@brunswicksouth.example")!!
    val bill = emails.findByMessageId("bill-1@electriclight.example")!!
    val broken = emails.findByMessageId("broken-1@example.test")!!

    assertEquals(
        listOf("Zoo excursion", "Parent teacher interviews"),
        events.listForEmail(excursion.id!!).map { it.title }
    )
    assertEquals(setOf("bill-october.pdf", "logo.png"), attachments.listForEmail(bill.id!!).map { it.filename }.toSet())
    assertEquals(2, ruleMatches.listForEmail(bill.id!!).size)
    assertEquals(3, actionResults.listForEmail(bill.id!!).size)
    assertEquals(2, errors.listForEmail(broken.id!!).size)
    assertEquals(2L, errors.countForEmail(broken.id!!).toLong())
    assertEquals(0L, errors.countForEmail(bill.id!!).toLong())
  }

  @Test
  @TestTransaction
  fun `an email's type is the Type entity`() {
    val bill = emails.findByMessageId("bill-1@electriclight.example")!!

    assertEquals("bill", bill.type!!.systemKey)
    assertNull(emails.findByMessageId("sale-1@shop.example")!!.type)
  }

  private fun newEmail(messageId: String) = EmailEntity().apply {
    this.messageId = messageId
    messageHash = MessageIds.hash(messageId)
    status = EmailStatus.PROCESSED
    receivedAt = Instant.parse("2026-10-22T10:00:00Z")
  }

  // Processing runs (PLAN 5.6)

  @Test
  @TestTransaction
  fun `the latest run is the most recently started`() {
    val latest = runs.findLatest()!!

    assertEquals("succeeded", latest.status.code)
    assertNull(runs.findRunning())
  }

  // Soft delete and timestamps

  @Test
  @TestTransaction
  fun `marking a type deleted soft-deletes it`() {
    // REQ Types 5: "soft deleted, not removed from the database"
    val ebay = types.findActiveByName("Ebay")!!
    ebay.markDeleted()
    entityManager.flush()

    assertNull(types.findActiveByName("Ebay"))
    val row = dataSource.row("SELECT deleted, deleted_at FROM Types WHERE name = 'Ebay'")
    assertEquals(true, row["deleted"])
    assertNotNull(row["deleted_at"])
  }

  @Test
  @TestTransaction
  fun `created and last updated times are set automatically`() {
    // REQ Rules and Rule_Actions schema (created_at, last_updated_at)
    val rule = rules.findActiveByName(RuleType.ACTION, "Forward payslips")!!
    val created = rule.createdAt
    val updated = rule.lastUpdatedAt

    rule.description = "Changed description"
    entityManager.flush()

    assertEquals(created, rule.createdAt)
    assertTrue(rule.lastUpdatedAt!!.isAfter(updated), "last updated moves on: $updated -> ${rule.lastUpdatedAt}")
  }
}
