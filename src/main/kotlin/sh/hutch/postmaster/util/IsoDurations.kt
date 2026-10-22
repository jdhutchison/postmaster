package sh.hutch.postmaster.util

import java.time.Duration

/** Thrown for text that isn't a valid duration or duration list. */
class InvalidDurationException(message: String) : IllegalArgumentException(message)

/**
 * Parses full ISO 8601 durations (`PT1H`, `P1D`, `P1W`, `PT1.5S`) and the simplified form without
 * `P` and `T` (`1H`, `1h`, `4h`, `1d`, `1h30m`), in which `M` means minutes. Both are
 * case-insensitive. Years and months aren't supported because they have no fixed length.
 */
object IsoDurations {
  private val FULL = Regex(
      "P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.(\\d{1,9}))?S)?)?",
      RegexOption.IGNORE_CASE
  )
  private val SIMPLIFIED = Regex(
      "(?:(\\d+)W)?(?:(\\d+)D)?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?",
      RegexOption.IGNORE_CASE
  )

  /** Separator for duration lists, such as calendar notification periods. */
  const val LIST_SEPARATOR = ", "

  fun parse(text: String): Duration {
    try {
      FULL.matchEntire(text)?.let { match ->
        val g = match.groupValues
        val hasTime = text.contains('T', ignoreCase = true)
        val timeEmpty = g[3].isEmpty() && g[4].isEmpty() && g[5].isEmpty()
        if ((g[1].isEmpty() && g[2].isEmpty() && timeEmpty) || (hasTime && timeEmpty)) {
          throw invalid(text)
        }
        val nanos = if (g[6].isEmpty()) 0L else g[6].padEnd(9, '0').toLong()
        return build(g[1], g[2], g[3], g[4], g[5]).plusNanos(nanos)
      }
      SIMPLIFIED.matchEntire(text)?.let { match ->
        val g = match.groupValues
        if (g.drop(1).all { it.isEmpty() }) {
          throw invalid(text)
        }
        return build(g[1], g[2], g[3], g[4], g[5])
      }
    } catch (e: ArithmeticException) {
      throw InvalidDurationException("'$text' is too long a duration")
    } catch (e: NumberFormatException) {
      throw InvalidDurationException("'$text' is too long a duration")
    }
    throw invalid(text)
  }

  fun isValid(text: String): Boolean =
    try {
      parse(text)
      true
    } catch (e: InvalidDurationException) {
      false
    }

  /** Parses a comma plus space separated list of durations, such as `4h, 1d`. */
  fun parseList(text: String): List<Duration> {
    if (text.isEmpty()) {
      throw InvalidDurationException("The list of durations is empty")
    }
    return text.split(LIST_SEPARATOR).map { item ->
      if (item.isEmpty() || item != item.trim() || item.contains(',')) {
        throw InvalidDurationException(
            "'$text' is not a list of durations separated by a comma and a space, such as 4h, 1d"
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

  /** Formats in the simplified form, e.g. `1d2h` or `30m`; ISO 8601 if there are fractions of a second. */
  fun format(duration: Duration): String {
    if (duration.isNegative || duration.nano != 0) {
      return duration.toString()
    }
    if (duration.isZero) {
      return "0s"
    }
    val parts = listOf(
        duration.toDaysPart() to "d",
        duration.toHoursPart().toLong() to "h",
        duration.toMinutesPart().toLong() to "m",
        duration.toSecondsPart().toLong() to "s"
    )
    return parts.filter { it.first != 0L }.joinToString("") { "${it.first}${it.second}" }
  }

  private fun build(weeks: String, days: String, hours: String, minutes: String, seconds: String): Duration {
    var duration = Duration.ZERO
    if (weeks.isNotEmpty()) duration = duration.plusDays(Math.multiplyExact(weeks.toLong(), 7L))
    if (days.isNotEmpty()) duration = duration.plusDays(days.toLong())
    if (hours.isNotEmpty()) duration = duration.plusHours(hours.toLong())
    if (minutes.isNotEmpty()) duration = duration.plusMinutes(minutes.toLong())
    if (seconds.isNotEmpty()) duration = duration.plusSeconds(seconds.toLong())
    return duration
  }

  private fun invalid(text: String) = InvalidDurationException(
      "'$text' is not a valid duration. Use a full or simplified ISO 8601 duration such as PT1H, 1H or 1h"
  )
}
