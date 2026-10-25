package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.ConditionEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.models.ConditionAttribute
import sh.hutch.postmaster.models.ConditionJoin
import sh.hutch.postmaster.models.EmailFacts
import sh.hutch.postmaster.models.MatchType
import java.util.regex.PatternSyntaxException

/**
 * Decides whether rules match an email. A rule matches when every one of its conditions does, or
 * any one of them for a rule whose conditions are joined with OR (CLARIFICATIONS.md Q21, R8); match
 * types work as agreed in Q9 and Q10, ignoring case (D5).
 */
@ApplicationScoped
class RuleEngine {

  fun matches(rule: RuleEntity, facts: EmailFacts): Boolean =
    rule.conditions.isNotEmpty() && when (rule.conditionJoin) {
      ConditionJoin.ALL -> rule.conditions.all { conditionMatches(it, facts) }
      ConditionJoin.ANY -> rule.conditions.any { conditionMatches(it, facts) }
    }

  fun conditionMatches(condition: ConditionEntity, facts: EmailFacts): Boolean {
    val test = condition.test.orEmpty()
    return when (condition.attribute) {
      ConditionAttribute.EVENTS -> listMatches(condition.matchType, test, facts.events)
      ConditionAttribute.ATTACHMENTS -> listMatches(condition.matchType, test, facts.attachments)
      ConditionAttribute.SENDER -> when (condition.matchType) {
        // The display name also counts for the looser match types
        MatchType.CONTAINS, MatchType.REGEX ->
          listOfNotNull(facts.sender, facts.senderName).any { scalarMatches(condition.matchType, test, it) }
        else -> scalarMatches(condition.matchType, test, facts.sender)
      }
      ConditionAttribute.SUBJECT -> scalarMatches(condition.matchType, test, facts.subject)
      ConditionAttribute.MESSAGE -> scalarMatches(condition.matchType, test, facts.message)
      ConditionAttribute.BILL_URL -> scalarMatches(condition.matchType, test, facts.billUrl)
      ConditionAttribute.TYPE -> scalarMatches(condition.matchType, test, facts.type)
      ConditionAttribute.LINK -> scalarMatches(condition.matchType, test, facts.link)
    }
  }

  private fun scalarMatches(matchType: MatchType, test: String, value: String?): Boolean {
    if (value == null) {
      return false
    }
    return when (matchType) {
      MatchType.IS -> value.trim().equals(test.trim(), ignoreCase = true)
      MatchType.CONTAINS -> value.contains(test, ignoreCase = true)
      MatchType.STARTS_WITH -> value.trim().startsWith(test.trim(), ignoreCase = true)
      MatchType.REGEX -> regexMatches(test, value)
      MatchType.HAS, MatchType.IS_PRESENT -> value.isNotBlank()
    }
  }

  private fun listMatches(matchType: MatchType, test: String, values: List<String>): Boolean =
    when (matchType) {
      MatchType.HAS, MatchType.IS_PRESENT -> values.isNotEmpty()
      else -> values.any { scalarMatches(matchType, test, it) }
    }

  private fun regexMatches(pattern: String, value: String): Boolean =
    try {
      Regex(pattern).containsMatchIn(value)
    } catch (e: PatternSyntaxException) {
      false
    }

  companion object {
    /** The facts rules test about [email], from its current state. */
    fun factsOf(email: EmailEntity): EmailFacts =
      EmailFacts(
          subject = email.subject,
          sender = email.sender,
          senderName = email.senderName,
          message = email.message,
          events = email.events.map { it.title },
          attachments = email.attachments.map { it.filename },
          billUrl = email.billDownloadUrl,
          type = email.type?.name,
          link = email.link
      )
  }
}
