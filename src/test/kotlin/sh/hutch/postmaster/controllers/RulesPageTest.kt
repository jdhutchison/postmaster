package sh.hutch.postmaster.controllers

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.RuleActionRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.testsupport.AdminProfile
import sh.hutch.postmaster.testsupport.flashMessages
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.htmx
import sh.hutch.postmaster.testsupport.parseHtml
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.testId
import sh.hutch.postmaster.testsupport.toasts

// REQ Rules 1-7, Creating a new rule 1-11, Editing a rule 1-13; CLAR Q9,
// Q10, Q16, D5, D6, D20
@QuarkusTest
@TestProfile(AdminProfile::class)
class RulesPageTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var rules: RuleRepository

  @Inject
  lateinit var ruleActions: RuleActionRepository

  @Inject
  lateinit var types: TypeRepository

  @Test
  fun `each rule type has its own page listing only its rules`() {
    // REQ Rules 1, 2, 3
    val classification = getHtml("/rules/classification")
    val action = getHtml("/rules/action")

    assertEquals("Classification rules", classification.testId("page-title").text())
    assertEquals("Action rules", action.testId("page-title").text())
    val newsletters = rules.findActiveByName(RuleType.CLASSIFICATION, "School newsletters")!!
    val payBills = rules.findActiveByName(RuleType.ACTION, "Pay bills")!!
    assertEquals(1, classification.select("[data-testid=rule-row-${newsletters.id}]").size)
    assertEquals(0, classification.select("[data-testid=rule-row-${payBills.id}]").size)
    assertEquals(1, action.select("[data-testid=rule-row-${payBills.id}]").size)
    assertTrue(classification.select("[data-testid^=rule-row-]").none { it.text().contains("Retired classification") })
    assertEquals(404, given().get("/rules/other").statusCode)
  }

  @Test
  fun `each row has edit and delete icons and an enabled switch showing the rule's state`() {
    // REQ Rules 3, 4, 6
    val page = getHtml("/rules/classification")
    val disabled = rules.findActiveByName(RuleType.CLASSIFICATION, "Disabled classification")!!
    val enabled = rules.findActiveByName(RuleType.CLASSIFICATION, "Bills by subject")!!

    assertFalse(page.testId("enabled-rule-${disabled.id}").hasAttr("checked"))
    assertTrue(page.testId("enabled-rule-${enabled.id}").hasAttr("checked"))
    assertEquals("switch", page.testId("enabled-rule-${enabled.id}").attr("role"))
    assertEquals("/mail/rules/classification/${enabled.id}/edit", page.testId("edit-rule-${enabled.id}").attr("href"))
    assertEquals("Delete rule Bills by subject?", page.testId("delete-rule-${enabled.id}").attr("hx-confirm"))
    assertEquals("/mail/rules/classification/new", page.testId("new-rule").attr("href"), "REQ Rules 7")
  }

  @Test
  fun `a rule is enabled, disabled and deleted from the list`() {
    val rule = rules.findActiveByName(RuleType.ACTION, "Forward payslips")!!

    assertEquals(listOf("success" to "Disabled rule Forward payslips"), htmx().formParam("enabled", "false")
        .post("/rules/action/${rule.id}/enabled").toasts())
    assertEquals(false, dataSource.row("SELECT enabled FROM Rules WHERE id = ?", rule.id)["enabled"])
    assertEquals(listOf("success" to "Enabled rule Forward payslips"), htmx().formParam("enabled", "true")
        .post("/rules/action/${rule.id}/enabled").toasts())
    assertEquals(listOf("success" to "Deleted rule Forward payslips"), htmx().delete("/rules/action/${rule.id}")
        .toasts())
    assertEquals(true, dataSource.row("SELECT deleted FROM Rules WHERE id = ?", rule.id)["deleted"],
        "REQ Editing a rule 5")
  }

  @Test
  fun `the create page offers only the type of rule being listed`() {
    // REQ Creating a new rule 2
    val page = getHtml("/rules/action/new")

    assertEquals("New action rule", page.testId("page-title").text())
    assertEquals(0, page.select("[name=ruleType], [name=type]").size, "the rule type isn't a form field")
    assertEquals(1, page.select("[data-testid=rule-form] input[name=name]").size)
    assertEquals("number", page.select("input[name=priority]").attr("type"))
    assertEquals("0", page.select("input[name=priority]").attr("min"))
  }

  @Test
  fun `the match type options say which attributes they apply to`() {
    // CLAR Q9
    val page = getHtml("/rules/classification/new")
    val options = page.select("template, [data-testid=conditions]").select("select[name$=matchType] option")
        .associate { it.attr("value") to it.attr("data-attributes").split(Regex("[ ,]+"))
            .filter { a -> a.isNotEmpty() }.toSet() }

    assertEquals(setOf("events", "attachments"), options["has"])
    assertEquals(setOf("subject", "sender", "message", "link"), options["contains"])
  }

  @Test
  fun `a rule is created from the form`() {
    // REQ Creating a new rule 3-11
    val bill = types.findBill()!!
    val created = form()
        .formParam("name", "Electricity").formParam("description", "Power bills").formParam("priority", "")
        .formParam("conditions[0].attribute", "sender").formParam("conditions[0].matchType", "contains")
        .formParam("conditions[0].test", "electric")
        .formParam("conditions[5].attribute", "attachments").formParam("conditions[5].matchType", "has")
        .formParam("actions[0].actionType", "set_type").formParam("actions[0].typeId", bill.id.toString())
        .formParam("actions[0].enabled", "true")
        .formParam("actions[2].actionType", "move").formParam("actions[2].destination", "Folders/Bills")
        .formParam("actions[2].enabled", "true")
        .post("/rules/classification")

    assertEquals(303, created.statusCode, created.asString())
    assertEquals(listOf("Created rule Electricity"), created.flashMessages())
    val rule = rules.findActiveByName(RuleType.CLASSIFICATION, "Electricity")!!
    assertEquals(0, rule.priority)
    assertEquals(listOf("sender", "attachments"), rule.conditions.map { it.attribute.code })
    assertEquals(listOf("set_type", "move"), ruleActions.listActiveForRule(rule.id!!).map { it.actionType })
  }

  @Test
  fun `form errors are shown against the rows they belong to`() {
    // REQ Creating a new rule 4-11
    val refused = form()
        .formParam("name", "").formParam("description", "x").formParam("priority", "-2")
        .formParam("conditions[4].attribute", "events").formParam("conditions[4].matchType", "contains")
        .formParam("conditions[4].test", "zoo")
        .formParam("actions[1].actionType", "move").formParam("actions[1].destination", "")
        .formParam("actions[1].enabled", "true")
        .post("/rules/classification")

    assertEquals(422, refused.statusCode)
    val page = parseHtml(refused.asString())
    for (key in listOf("name", "priority", "conditions[4].matchType", "actions[1].destination")) {
      assertTrue(page.select("[data-testid=\"error-$key\"]").text().isNotBlank(), "error for $key")
    }
  }

  @Test
  fun `a type that must match exactly is chosen from the types, and other type matches are typed`() {
    val bill = types.findBill()!!
    val refused = form()
        .formParam("name", "").formParam("description", "x")
        .formParam("conditions[0].attribute", "type").formParam("conditions[0].matchType", "is")
        .formParam("conditions[0].test", bill.name)
        .formParam("conditions[1].attribute", "type").formParam("conditions[1].matchType", "regex")
        .formParam("conditions[1].test", "school$")
        .formParam("conditions[2].attribute", "type").formParam("conditions[2].matchType", "is")
        .formParam("conditions[2].test", "Retired type")
        .formParam("actions[0].actionType", "mark_read").formParam("actions[0].enabled", "true")
        .post("/rules/classification")

    assertEquals(422, refused.statusCode)
    val page = parseHtml(refused.asString())
    val exact = page.select("#condition-0-test-type").first()!!
    assertFalse(exact.hasAttr("disabled"))
    assertEquals(bill.name, exact.select("option[selected]").`val`())
    assertTrue(exact.select("option").map { it.`val`() }.containsAll(types.listActive().map { it.name }))
    assertTrue(page.select("#condition-0-test").first()!!.hasAttr("disabled"), "only the dropdown is submitted")
    assertEquals("condition-0-test-type", page.select("label[for^=condition-0-test]").attr("for"))

    assertFalse(page.select("#condition-1-test").first()!!.hasAttr("disabled"))
    assertEquals("school$", page.select("#condition-1-test").`val`())
    assertTrue(page.select("#condition-1-test-type").first()!!.hasAttr("disabled"))

    assertEquals("Retired type", page.select("#condition-2-test-type option[selected]").`val`(), "a value that isn't a type is kept")
  }

  @Test
  fun `a duplicate name ignoring case is refused within the rule type`() {
    // CLAR D20
    val refused = form()
        .formParam("name", "PAY BILLS").formParam("description", "x")
        .formParam("conditions[0].attribute", "subject").formParam("conditions[0].matchType", "contains")
        .formParam("conditions[0].test", "x")
        .formParam("actions[0].actionType", "mark_read").formParam("actions[0].enabled", "true")
        .post("/rules/action")

    assertEquals(422, refused.statusCode)
    assertTrue(parseHtml(refused.asString()).testId("error-name").text().isNotBlank())
  }

  @Test
  fun `the edit page shows the name read-only and the rule's conditions and actions`() {
    // REQ Editing a rule 1, 2
    val rule = rules.findActiveByName(RuleType.ACTION, "School events")!!
    val page = getHtml("/rules/action/${rule.id}/edit")

    assertEquals("School events", page.testId("rule-name").text().trim())
    assertEquals(0, page.select("[data-testid=rule-form] input[name=name]").size)
    assertEquals(2, page.select("[data-testid=conditions] [data-testid=condition-row]").size)
    assertEquals(2, page.select("[data-testid=actions] [data-testid=action-row]").size)
    assertEquals(1, page.select("[data-testid=delete-rule]").size, "REQ Editing a rule 5")
  }

  private fun form(): RequestSpecification = given().redirects().follow(false)
      .contentType("application/x-www-form-urlencoded")
}
