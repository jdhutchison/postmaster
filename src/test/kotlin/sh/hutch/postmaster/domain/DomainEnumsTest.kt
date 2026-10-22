package sh.hutch.postmaster.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The stored values and rules behind the rule, email and setting enums */
class DomainEnumsTest {
  @Test
  fun `the match types that apply to each attribute are the Q9 matrix`() {
    // CLAR Q9 (the condition compatibility table, confirmed by the user)
    val expected = mapOf(
        MatchType.IS to setOf("subject", "sender", "bill_url", "type", "link"),
        MatchType.CONTAINS to setOf("subject", "sender", "message", "link"),
        MatchType.STARTS_WITH to setOf("subject", "sender", "message", "bill_url", "link"),
        MatchType.REGEX to setOf("subject", "sender", "message", "events", "attachments", "bill_url", "type", "link"),
        MatchType.HAS to setOf("events", "attachments"),
        MatchType.IS_PRESENT to setOf("message", "events", "attachments", "bill_url", "type", "link")
    )
    for (matchType in MatchType.entries) {
      val applies = ConditionAttribute.entries.filter { matchType.appliesTo(it) }.map { it.code }.toSet()
      assertEquals(expected.getValue(matchType), applies, "attributes for ${matchType.code}")
    }
  }

  @Test
  fun `has and is_present take no test value`() {
    // CLAR Q10: has means "has any" and is_present means non-empty; neither has a test value
    assertFalse(MatchType.HAS.needsTest)
    assertFalse(MatchType.IS_PRESENT.needsTest)
    for (matchType in listOf(MatchType.IS, MatchType.CONTAINS, MatchType.STARTS_WITH, MatchType.REGEX)) {
      assertTrue(matchType.needsTest, "${matchType.code} needs a test value")
    }
  }

  @Test
  fun `condition attributes are the eight in the requirements`() {
    // REQ Creating a new rule 5
    assertEquals(
        listOf("subject", "sender", "message", "events", "attachments", "bill_url", "type", "link"),
        ConditionAttribute.entries.map { it.code }
    )
  }

  @Test
  fun `match types are the six in the requirements`() {
    // REQ Creating a new rule 6
    assertEquals(
        setOf("is", "contains", "starts_with", "regex", "has", "is_present"),
        MatchType.entries.map { it.code }.toSet()
    )
  }

  @Test
  fun `the built-in action types are those in the requirements`() {
    // REQ Creating a new rule 9 and Rule_Actions schema; CLAR Q13 revision
    // (no set_action_needed action)
    assertEquals(
        setOf(
            "set_type", "move", "check_for_events", "download_attachments", "set_reply_needed",
            "fetch_link_contents", "create_events", "skip_processing", "mark_read", "mark_unread", "process_bill",
            "forward_email", "delete"
        ),
        ActionTypes.BUILT_IN.toSet()
    )
  }

  @Test
  fun `rule types are classification and action`() {
    // REQ Rules schema "type, the type of rule, one of classification or action"
    assertEquals(setOf("classification", "action"), RuleType.entries.map { it.code }.toSet())
  }

  @Test
  fun `email statuses are the four agreed`() {
    // CLAR D1
    assertEquals(setOf("pending", "processed", "skipped", "failed"), EmailStatus.entries.map { it.code }.toSet())
  }

  @Test
  fun `every enum value is found by its code, and unknown codes are rejected`() {
    assertEquals(RuleType.ACTION, RuleType.fromCode("action"))
    assertEquals(MatchType.STARTS_WITH, MatchType.fromCode("starts_with"))
    assertEquals(ConditionAttribute.BILL_URL, ConditionAttribute.fromCode("bill_url"))
    assertEquals(EmailStatus.SKIPPED, EmailStatus.fromCode("skipped"))
    for (value in MatchType.entries) {
      assertEquals(value, MatchType.fromCode(value.code))
    }
    for (value in ConditionAttribute.entries) {
      assertEquals(value, ConditionAttribute.fromCode(value.code))
    }
    for (value in EmailStatus.entries) {
      assertEquals(value, EmailStatus.fromCode(value.code))
    }
    for (value in SettingType.entries) {
      assertEquals(value, SettingType.fromCode(value.code))
    }
    assertThrows<IllegalArgumentException> { RuleType.fromCode("other") }
    assertThrows<IllegalArgumentException> { MatchType.fromCode("has_any") }
    assertThrows<IllegalArgumentException> { EmailStatus.fromCode("done") }
  }
}
