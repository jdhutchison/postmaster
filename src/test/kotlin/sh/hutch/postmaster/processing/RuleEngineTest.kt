package sh.hutch.postmaster.processing

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import sh.hutch.postmaster.entities.ConditionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.models.ConditionAttribute
import sh.hutch.postmaster.models.ConditionJoin
import sh.hutch.postmaster.models.EmailFacts
import sh.hutch.postmaster.models.MatchType
import sh.hutch.postmaster.services.RuleEngine

/**
 * Testing a rule's conditions against an email (REQ Classifying emails 2;
 * CLAR Q9, Q10, Q21 and R8: all conditions must match, or any one for a rule
 * joined with OR; D5: is, contains and
 * starts_with ignore case, regex is case-sensitive unless it uses (?i)).
 */
@QuarkusTest
class RuleEngineTest {
  @Inject
  lateinit var engine: RuleEngine

  private val facts = EmailFacts(
      subject = "Your Electricity Bill is ready",
      sender = "billing@electric.example",
      senderName = "Electric Light Company",
      message = "Your bill of \$123.45 is due by 25 Nov 2026.",
      events = listOf("Zoo excursion", "Parent teacher interviews"),
      attachments = listOf("bill-october.pdf", "logo.png"),
      billUrl = null,
      type = "Bill",
      link = "https://electric.example/pay"
  )

  private val empty = EmailFacts(null, null, null, null, emptyList(), emptyList(), null, null, null)

  private fun condition(attribute: String, matchType: String, test: String?) = ConditionEntity().apply {
    this.attribute = ConditionAttribute.fromCode(attribute)
    this.matchType = MatchType.fromCode(matchType)
    this.test = test
  }

  private fun rule(vararg conditions: ConditionEntity) = RuleEntity().apply { this.conditions.addAll(conditions) }

  private fun matches(attribute: String, matchType: String, test: String?, on: EmailFacts = facts) =
      engine.conditionMatches(condition(attribute, matchType, test), on)

  @Test
  fun `every condition must match`() {
    // CLAR Q21
    val subject = condition("subject", "contains", "bill")
    val sender = condition("sender", "contains", "electric")
    val other = condition("sender", "contains", "gas")

    assertTrue(engine.matches(rule(subject, sender), facts))
    assertFalse(engine.matches(rule(subject, other), facts))
    assertFalse(engine.matches(rule(), facts), "a rule with no conditions never matches")
  }

  @Test
  fun `a rule joined with OR matches when any condition does`() {
    // CLAR R8
    val subject = condition("subject", "contains", "bill")
    val gas = condition("sender", "contains", "gas")
    val water = condition("sender", "contains", "water")
    fun any(vararg conditions: ConditionEntity) = rule(*conditions).apply { conditionJoin = ConditionJoin.ANY }

    assertTrue(engine.matches(any(gas, subject), facts))
    assertFalse(engine.matches(any(gas, water), facts))
    assertFalse(engine.matches(any(), facts), "a rule with no conditions never matches")
    assertEquals(ConditionJoin.ALL, RuleEntity().conditionJoin, "rules are AND unless set otherwise")
  }

  @ParameterizedTest(name = "{0} {1} \"{2}\" -> {3}")
  @CsvSource(
      // is: whole value, trimmed, ignoring case
      "subject, is, '  your electricity bill IS READY ', true",
      "subject, is, Your Electricity Bill, false",
      "type, is, bill, true",
      "type, is, Bills, false",
      "link, is, https://electric.example/pay, true",
      // contains, ignoring case
      "subject, contains, ELECTRICITY, true",
      "message, contains, due by 25 nov, true",
      "link, contains, /pay, true",
      "subject, contains, gas, false",
      // starts_with, trimmed, ignoring case
      "subject, starts_with, your electricity, true",
      "subject, starts_with, Electricity, false",
      "link, starts_with, HTTPS://electric, true",
      // regex: case-sensitive unless (?i)
      "subject, regex, Electricity\\s+Bill, true",
      "subject, regex, electricity bill, false",
      "subject, regex, (?i)electricity bill, true",
      "message, regex, \\$\\d+\\.\\d{2}, true",
      "type, regex, ^B, true",
      // is_present
      "link, is_present, , true",
      "bill_url, is_present, , false",
      "message, is_present, , true"
  )
  fun `scalar attributes match as agreed`(attribute: String, matchType: String, test: String?, expected: Boolean) {
    assertEquals(expected, matches(attribute, matchType, test))
  }

  @ParameterizedTest(name = "{0} {1} \"{2}\" -> {3}")
  @CsvSource(
      "events, has, , true",
      "attachments, has, , true",
      "events, is_present, , true",
      "events, regex, ^Zoo, true",
      "events, regex, ^zoo, false",
      "attachments, regex, (?i)\\.PDF$, true",
      "attachments, regex, \\.docx$, false"
  )
  fun `list attributes match any item`(attribute: String, matchType: String, test: String?, expected: Boolean) {
    // CLAR Q10: has is "has any"
    assertEquals(expected, matches(attribute, matchType, test))
  }

  @Test
  fun `empty lists and missing values match nothing but a false is_present`() {
    for (attribute in listOf("events", "attachments")) {
      assertFalse(matches(attribute, "has", null, empty))
      assertFalse(matches(attribute, "is_present", null, empty))
      assertFalse(matches(attribute, "regex", ".*", empty))
    }
    for (attribute in listOf("subject", "sender", "message", "link", "bill_url", "type")) {
      assertFalse(matches(attribute, "is_present", null, empty), attribute)
      assertFalse(matches(attribute, "regex", ".*", empty), attribute)
    }
    assertFalse(matches("subject", "contains", "", empty))
    assertFalse(matches("subject", "is", "", empty))
  }

  @Test
  fun `the sender matches by address, and by name for contains and regex`() {
    assertTrue(matches("sender", "is", "BILLING@electric.example"))
    assertFalse(matches("sender", "is", "Electric Light Company"))
    assertTrue(matches("sender", "starts_with", "billing@"))
    assertFalse(matches("sender", "starts_with", "Electric"))
    assertTrue(matches("sender", "contains", "light company"))
    assertTrue(matches("sender", "contains", "@electric.example"))
    assertTrue(matches("sender", "regex", "^Electric Light"))
  }

  @Test
  fun `an invalid regex matches nothing`() {
    assertFalse(matches("subject", "regex", "([a-z"))
  }
}
