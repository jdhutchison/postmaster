package sh.hutch.postmaster.services

import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.util.Reminder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Writes events as iCalendar (RFC 5545) for khal to import. */
object IcsWriter {
  private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
  private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DEFAULT_LENGTH = Duration.ofHours(1)

  /**
   * The event as a VCALENDAR. Timed events are converted from wall-clock time in [zone] to UTC;
   * all-day events use dates. [CalendarEvent.uid] must be set.
   */
  fun event(event: CalendarEvent, zone: ZoneId, now: Instant = Instant.now()): String {
    val uid = requireNotNull(event.uid) { "The event needs a UID" }
    val lines = mutableListOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//hutch.sh//Postmaster//EN",
        "BEGIN:VEVENT",
        "UID:${escape(uid)}",
        "DTSTAMP:${UTC.format(now)}"
    )
    if (event.allDay) {
      val day = event.startsAt.toLocalDate()
      val end = event.endsAt?.toLocalDate()?.takeIf { it.isAfter(day) } ?: day.plusDays(1)
      lines += "DTSTART;VALUE=DATE:${DATE.format(day)}"
      lines += "DTEND;VALUE=DATE:${DATE.format(end)}"
    } else {
      val start = event.startsAt.atZone(zone).toInstant()
      val end = event.endsAt?.atZone(zone)?.toInstant()?.takeIf { it.isAfter(start) } ?: start.plus(DEFAULT_LENGTH)
      lines += "DTSTART:${UTC.format(start)}"
      lines += "DTEND:${UTC.format(end)}"
    }
    lines += "SUMMARY:${escape(event.title)}"
    event.location?.takeIf { it.isNotBlank() }?.let { lines += "LOCATION:${escape(it)}" }
    event.notes?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:${escape(it)}" }
    for (alarm in event.alarms) {
      val trigger = when (alarm) {
        is Reminder.Before -> if (alarm.duration.isNegative || alarm.duration.isZero) continue else "TRIGGER:-${alarm.duration}"
        // A time of day is absolute, so it's converted to UTC like the start (CLARIFICATIONS.md R9)
        is Reminder.At -> "TRIGGER;VALUE=DATE-TIME:${UTC.format(alarm.on(event.startsAt.toLocalDate()).atZone(zone).toInstant())}"
      }
      lines += "BEGIN:VALARM"
      lines += "ACTION:DISPLAY"
      lines += "DESCRIPTION:${escape(event.title)}"
      lines += trigger
      lines += "END:VALARM"
    }
    lines += "END:VEVENT"
    lines += "END:VCALENDAR"
    return lines.joinToString("") { fold(it) + "\r\n" }
  }

  /** Escapes TEXT values: backslash, semicolon, comma and newlines. */
  fun escape(text: String): String =
    text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

  /** Folds a content line into lines of at most 75 octets, continuing with a space (RFC 5545 3.1). */
  fun fold(line: String): String {
    val out = StringBuilder()
    var octets = 0
    var limit = 75
    for (ch in line.codePoints().toArray()) {
      val size = String(Character.toChars(ch)).toByteArray(StandardCharsets.UTF_8).size
      if (octets + size > limit) {
        out.append("\r\n ")
        octets = 0
        limit = 74
      }
      out.appendCodePoint(ch)
      octets += size
    }
    return out.toString()
  }
}
