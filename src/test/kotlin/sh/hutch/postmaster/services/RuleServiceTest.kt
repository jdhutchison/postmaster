package sh.hutch.postmaster.services

import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.ActionInput
import sh.hutch.postmaster.models.CalendarInput
import sh.hutch.postmaster.models.ConditionInput
import sh.hutch.postmaster.models.RuleInput
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.RuleActionRepository
import sh.hutch.postmaster.repositories.TypeRepository

// REQ Rules, Creating a new rule, Editing a rule; CLAR Q9 (match types per
// attribute), Q10 (has/is_present take no test), Q16 (process_bill needs a
// calendar), Q21 (conditions combine with AND), D5, D6, D20
@QuarkusTest
class RuleServiceTest {
  @Inject
  lateinit var service: RuleService

  @Inject
  lateinit var types: TypeRepository

  @Inject
  lateinit var calendars: CalendarService

  @Inject
  lateinit var actions: RuleActionRepository

  @Inject
  lateinit var entityManager: EntityManager

  /** Frees the khal calendar names used here */
  @AfterEach
  fun deleteCalendars() {
    calendars.list().forEach { calendars.delete(it.id!!) }
  }

  private fun condition(attribute: String, matchType: String, test: String? = "x", index: Int = 0, id: Long? = null) =
      ConditionInput(index, id, attribute, matchType, test)

  private fun action(
      type: String,
      index: Int = 0,
      destination: String? = null,
      typeId: String? = null,
      calendarId: String? = null,
      notifications: String? = null,
      enabled: Boolean = true,
      id: Long? = null
  ) = ActionInput(index, id, type, destination, typeId, calendarId, notifications, enabled)

  private fun rule(
      name: String = "Rule",
      description: String? = "Does things",
      priority: String? = null,
      conditions: List<ConditionInput> = listOf(condition("subject", "contains", "invoice")),
      actions: List<ActionInput> = listOf(action("mark_read"))
  ) = RuleInput(name, description, priority, conditions, actions)

  private fun errors(type: RuleType = RuleType.CLASSIFICATION, input: RuleInput): Map<String, String> =
      assertThrows<ValidationException> { service.create(type, input) }.errors

  @Test
  @TestTransaction
  fun `a rule is created with a name, description, conditions and actions`() {
    val created = service.create(RuleType.CLASSIFICATION, rule(name = "Invoices", priority = "7"))

    assertEquals("Invoices", created.name)
    assertEquals(7, created.priority)
    assertTrue(created.enabled)
    assertEquals(RuleType.CLASSIFICATION, created.ruleType)
  }

  @Test
  fun `name and description are required, and priority defaults to zero`() {
    // REQ Creating a new rule 3
    assertTrue(setOf("name", "description").all { it in errors(input = rule(name = " ", description = "")) })
    assertEquals(0, service.create(RuleType.ACTION, rule(name = "No priority", priority = " ")).priority)
  }

  @Test
  fun `priority must be a whole number of zero or more`() {
    // REQ Creating a new rule 3: "must be set to a positive integer if a value is entered"
    for (priority in listOf("-1", "1.5", "high")) {
      assertTrue("priority" in errors(input = rule(name = "P $priority", priority = priority)), priority)
    }
    assertEquals(0, service.create(RuleType.ACTION, rule(name = "Zero", priority = "0")).priority)
  }

  @Test
  fun `a rule needs at least one condition and one action`() {
    // REQ Creating a new rule 4 and 8; Editing a rule 7 and 11
    assertTrue("conditions" in errors(input = rule(conditions = emptyList())))
    assertTrue("actions" in errors(input = rule(actions = emptyList())))
  }

  @Test
  fun `a condition's match type must apply to its attribute`() {
    // CLAR Q9
    val bad = rule(
        conditions = listOf(condition("subject", "contains", index = 0), condition("events", "contains", index = 3))
    )

    assertTrue("conditions[3].matchType" in errors(input = bad), "events can't use contains")
    assertTrue("conditions[0].attribute" in errors(input = rule(conditions = listOf(condition("colour", "is")))))
    assertTrue("conditions[0].matchType" in errors(input = rule(conditions = listOf(condition("subject", "has_any")))))
  }

  @Test
  fun `has and is_present take no test value, and the others need one`() {
    // CLAR Q10
    val created = service.create(
        RuleType.ACTION,
        rule(
            name = "Has things",
            conditions = listOf(
                condition("attachments", "has", "ignored", 0),
                condition("link", "is_present", "ignored", 1),
                condition("sender", "is", "a@b.example", 2)
            )
        )
    )
    assertEquals(listOf(null, null, "a@b.example"), created.conditions.map { it.test })

    assertTrue("conditions[0].test" in errors(input = rule(conditions = listOf(condition("subject", "contains", " ")))))
  }

  @Test
  fun `a regex test must be a valid regular expression`() {
    val badRegex = rule(conditions = listOf(condition("subject", "regex", "([a-z")))
    assertTrue("conditions[0].test" in errors(input = badRegex))
    val goodRegex = rule(name = "Regex", conditions = listOf(condition("subject", "regex", "(?i)bill|invoice")))
    service.create(RuleType.ACTION, goodRegex)
  }

  @Test
  fun `move and forward actions need a destination`() {
    // REQ Creating a new rule 10: 'to:' must be a non-empty string
    assertTrue("actions[0].destination" in errors(input = rule(actions = listOf(action("move", destination = " ")))))
    assertTrue("actions[0].destination" in errors(input = rule(actions = listOf(action("forward_email")))))
    val badAddress = rule(actions = listOf(action("forward_email", destination = "not an address")))
    assertTrue("actions[0].destination" in errors(input = badAddress))
    val ok = service.create(
        RuleType.ACTION,
        rule(
            name = "Destinations",
            actions = listOf(action("move", 0, "Folders/Bills"), action("forward_email", 1, "a@b.example"))
        )
    )
    assertEquals(listOf("Folders/Bills", "a@b.example"), ok.actions.map { it.destination })
  }

  @Test
  fun `set_type needs one of the defined types`() {
    // REQ Creating a new rule 11
    assertTrue("actions[0].typeId" in errors(input = rule(actions = listOf(action("set_type")))))
    assertTrue("actions[0].typeId" in errors(input = rule(actions = listOf(action("set_type", typeId = "999999")))))
    val bill = types.findBill()!!
    val input = rule(name = "Bills", actions = listOf(action("set_type", typeId = bill.id.toString())))
    val created = service.create(RuleType.CLASSIFICATION, input)
    assertEquals("Bill", created.actions.single().type!!.name)
  }

  @Test
  fun `process_bill needs a calendar for the reminder, and create_events may leave it to the LLM`() {
    // CLAR Q16 and Q15
    val calendar = calendars.create(CalendarInput("Bills", "Bill reminders", "#ffb86c", true, null))
    assertTrue("actions[0].calendarId" in errors(input = rule(actions = listOf(action("process_bill")))))

    val created = service.create(
        RuleType.ACTION,
        rule(
            name = "Calendars",
            actions = listOf(
                action("process_bill", 0, calendarId = calendar.id.toString()),
                action("create_events", 1, notifications = "1d, 2h"),
                action("move", 2, destination = "Folders/x", calendarId = calendar.id.toString(), typeId = "1")
            )
        )
    )
    assertEquals("Bills", created.actions[0].calendar!!.name)
    assertNull(created.actions[1].calendar)
    assertEquals("1d, 2h", created.actions[1].notifications)
    assertNull(created.actions[2].calendar, "fields that don't apply are not stored")
    assertNull(created.actions[2].type)
    val badNotifications = rule(actions = listOf(action("create_events", notifications = "1d,2h")))
    assertTrue("actions[0].notifications" in errors(input = badNotifications))
  }

  @Test
  fun `unknown action types are rejected`() {
    // CLAR Q13 revision: there is no set_action_needed action
    assertTrue("actions[0].actionType" in errors(input = rule(actions = listOf(action("set_action_needed")))))
  }

  @Test
  fun `rule names are unique ignoring case within a rule type`() {
    // CLAR D20 (D5: unique per rule type)
    service.create(RuleType.CLASSIFICATION, rule(name = "Newsletters"))

    val error = assertThrows<DuplicateNameException> {
      service.create(RuleType.CLASSIFICATION, rule(name = "NEWSLETTERS"))
    }
    assertEquals("name", error.field)
    service.create(RuleType.ACTION, rule(name = "newsletters"))
  }

  @Test
  @TestTransaction
  fun `editing keeps the name and updates conditions and actions`() {
    // REQ Editing a rule 2, 3, 6, 9, 10, 12
    val created = service.create(
        RuleType.ACTION,
        rule(
            name = "Editable",
            conditions = listOf(condition("subject", "contains", "a", 0), condition("sender", "contains", "b", 1)),
            actions = listOf(action("mark_read", 0), action("move", 1, "Folders/A"))
        )
    )
    val keptCondition = created.conditions[0].id
    val removedAction = created.actions[0].id!!
    val keptAction = created.actions[1].id

    val updated = service.update(
        RuleType.ACTION, created.id!!,
        RuleInput(
            "Renamed", "Changed", "3",
            listOf(
                condition("subject", "contains", "changed", 0, keptCondition),
                condition("link", "is_present", null, 1)
            ),
            listOf(action("move", 0, "Folders/B", enabled = false, id = keptAction), action("mark_unread", 1))
        )
    )
    entityManager.flush()

    assertEquals("Editable", updated.name, "REQ Editing a rule 2: the name can't be edited")
    assertEquals("Changed", updated.description)
    assertEquals(
        listOf("subject" to "changed", "link" to null),
        updated.conditions.map { it.attribute.code to it.test }
    )
    assertEquals(keptCondition, updated.conditions[0].id)
    val active = actions.listActiveForRule(created.id!!)
    assertEquals(listOf("move", "mark_unread"), active.map { it.actionType })
    assertFalse(active[0].enabled, "REQ Editing a rule 10: actions can be disabled")
    assertTrue(actions.findById(removedAction)!!.deleted, "removed actions are soft-deleted")
  }

  @Test
  @TestTransaction
  fun `rules can be disabled, enabled and soft-deleted`() {
    // REQ Rules 6; Editing a rule 4, 5
    val created = service.create(RuleType.CLASSIFICATION, rule(name = "Toggle"))

    assertFalse(service.setEnabled(RuleType.CLASSIFICATION, created.id!!, false).enabled)
    assertTrue(service.setEnabled(RuleType.CLASSIFICATION, created.id!!, true).enabled)
    service.delete(RuleType.CLASSIFICATION, created.id!!)
    assertTrue(created.deleted)
    assertTrue(service.list(RuleType.CLASSIFICATION).none { it.id == created.id })
  }

  @Test
  fun `a rule is only found under its own type`() {
    // REQ Creating a new rule 2: the type is implied by the list
    val created = service.create(RuleType.CLASSIFICATION, rule(name = "Typed"))

    assertThrows<jakarta.ws.rs.NotFoundException> { service.get(RuleType.ACTION, created.id!!) }
  }
}
