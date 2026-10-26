package sh.hutch.postmaster.util

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** When to be reminded of an event (CLARIFICATIONS.md R9). */
sealed interface Reminder {
  /** [duration] before the event starts, e.g. `4h` or `PT30M`. */
  data class Before(val duration: Duration) : Reminder

  /** At [time] on the day [days] before the event's day (0 is the day itself), e.g. `d-1@20:00`. */
  data class At(val days: Int, val time: LocalTime) : Reminder {
    /** When the reminder goes off for an event on [day], in the event's local time. */
    fun on(day: LocalDate): LocalDateTime = day.minusDays(days.toLong()).atTime(time)
  }
}

/**
 * Parses notification lists: durations before the event (see IsoDurations) and reminders at a time
 * of day, `d-1@20:00`, `d@8am` or `d-2@8:30pm`, separated by a comma and a space
 * (CLARIFICATIONS.md D6, R9). Times are 24-hour (`20:00`, ISO 8601) or 12-hour (`8pm`, `8:30am`).
 */
object Reminders {
  private val AT = Regex("d(?:-(\\d{1,3}))?@(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)?", RegexOption.IGNORE_CASE)

  fun parse(text: String): Reminder {
    if (text.startsWith("d", ignoreCase = true) && text.contains('@')) {
      return parseAt(text)
    }
    return try {
      Reminder.Before(IsoDurations.parse(text))
    } catch (e: InvalidDurationException) {
      throw invalid(text)
    }
  }

  private fun parseAt(text: String): Reminder.At {
    val match = AT.matchEntire(text) ?: throw invalid(text)
    val (days, hourText, minuteText, half) = match.destructured
    var hour = hourText.toInt()
    val minute = if (minuteText.isEmpty()) 0 else minuteText.toInt()
    if (half.isEmpty()) {
      // 24-hour times give the minutes: 20:00, not 20
      if (minuteText.isEmpty() || hour > 23) {
        throw invalid(text)
      }
    } else {
      if (hour !in 1..12) {
        throw invalid(text)
      }
      hour = hour % 12 + if (half.equals("pm", ignoreCase = true)) 12 else 0
    }
    if (minute > 59) {
      throw invalid(text)
    }
    return Reminder.At(if (days.isEmpty()) 0 else days.toInt(), LocalTime.of(hour, minute))
  }

  fun isValid(text: String): Boolean =
    try {
      parse(text)
      true
    } catch (e: InvalidDurationException) {
      false
    }

  /** Parses a comma plus space separated list, such as `4h, d-1@20:00`. */
  fun parseList(text: String): List<Reminder> {
    if (text.isEmpty()) {
      throw InvalidDurationException("The list of notifications is empty")
    }
    return text.split(IsoDurations.LIST_SEPARATOR).map { item ->
      if (item.isEmpty() || item != item.trim() || item.contains(',')) {
        throw InvalidDurationException(
            "'$text' is not a list of notifications separated by a comma and a space, such as 4h, d-1@20:00"
        )
      }
      parse(item)
    }
  }

  fun isValidList(text: String): Boolean =
    try {
      parseList(text)
      true
    } catch (e: InvalidDurationException) {
      false
    }

  /** The list in [text], or empty if it isn't valid: for settings saved before they were checked. */
  fun parseListOrEmpty(text: String?): List<Reminder> =
    text?.let { runCatching { parseList(it) }.getOrNull() }.orEmpty()

  private fun invalid(text: String) = InvalidDurationException(
      "'$text' is not a notification. Use a duration before the event such as 4h or PT30M, " +
          "or a time of day before the event's day such as d-1@20:00 or d@8am"
  )
}
