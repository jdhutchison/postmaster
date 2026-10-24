package sh.hutch.postmaster.actions

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/** Finds a bill's amount and due date in its text (REQUIREMENTS.md "process bill", CLARIFICATIONS.md D14). */
object BillParser {
  private val AMOUNT = Regex("\\$\\s?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)")
  private val AMOUNT_KEYWORDS = Regex("(?i)amount due|total due|balance due|amount payable|total|due")
  private val DUE_PREFIX = "(?:(?:due|pay)\\s+by|due\\s+date)[^\\n]{0,60}?"
  private val DUE_NAMED = Regex("(?i)$DUE_PREFIX\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]{3,9})\\.?,?\\s+(\\d{4})")
  private val DUE_NUMERIC = Regex("(?i)$DUE_PREFIX\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})")
  private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

  /** The amount due: the first amount within 80 characters after a "due"/"total" phrase, else the first amount. */
  fun amount(text: String): BigDecimal? {
    val matches = AMOUNT.findAll(text).toList()
    if (matches.isEmpty()) {
      return null
    }
    val keywordEnds = AMOUNT_KEYWORDS.findAll(text).map { it.range.last + 1 }.toList()
    val preferred = matches.firstOrNull { match -> keywordEnds.any { end -> match.range.first >= end && match.range.first - end <= 80 } }
    return (preferred ?: matches.first()).groupValues[1].replace(",", "").toBigDecimalOrNull()
  }

  /** The due date after "due by", "pay by" or "due date": `1 Oct 2026`, `01 October 2026` or `1/10/2026`. */
  fun dueDate(text: String): LocalDate? {
    DUE_NAMED.find(text)?.let { match ->
      val month = MONTHS.indexOf(match.groupValues[2].lowercase(Locale.ROOT).take(3))
      val full = match.groupValues[2].lowercase(Locale.ROOT)
      if (month >= 0 && isMonthName(full, month)) {
        date(match.groupValues[3].toInt(), month + 1, match.groupValues[1].toInt())?.let { return it }
      }
    }
    DUE_NUMERIC.find(text)?.let { match ->
      return date(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt())
    }
    return null
  }

  private val FULL_MONTHS = listOf(
      "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"
  )

  /** Accepts the three letter abbreviation, "sept", or the full name. */
  private fun isMonthName(text: String, month: Int): Boolean =
    text.length == 3 || text == "sept" || text == FULL_MONTHS[month]

  private fun date(year: Int, month: Int, day: Int): LocalDate? =
    try {
      LocalDate.of(year, month, day)
    } catch (e: DateTimeException) {
      null
    }
}
