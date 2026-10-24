package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.CalendarEvent
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

// The .ics files khal imports (REQ create_events: "add notifications if the
// notifications value of the action is populated with one or more
// durations"; CLAR D8 (all-day vs timed, the timezone, default 1h), D18 (our UID))
class IcsWriterTest {
  private val melbourne = ZoneId.of("Australia/Melbourne")

  private fun event(
      title: String = "Zoo excursion",
      starts: String = "2026-11-05T09:00",
      ends: String? = null,
      allDay: Boolean = false,
      notes: String? = null,
      alarms: List<Duration> = emptyList()
  ) = CalendarEvent("uid-123", title, LocalDateTime.parse(starts), ends?.let { LocalDateTime.parse(it) }, allDay,
      "Melbourne Zoo", notes, alarms)

  private fun unfolded(ics: String) = ics.replace("\r\n ", "")

  @Test
  fun `a timed event is in UTC, converted from local time, an hour long by default`() {
    // 9am in Melbourne on 5 November (AEDT, UTC+11) is 10pm UTC the day before
    val ics = IcsWriter.event(event(), melbourne)

    assertTrue(ics.contains("DTSTART:20261104T220000Z\r\n"), ics)
    assertTrue(ics.contains("DTEND:20261104T230000Z\r\n"), ics)
    assertTrue(ics.contains("UID:uid-123\r\n"))
    assertTrue(ics.contains("SUMMARY:Zoo excursion\r\n"))
    assertTrue(ics.contains("LOCATION:Melbourne Zoo\r\n"))
  }

  @Test
  fun `an end time is used when known, and winter time is AEST`() {
    val ics = IcsWriter.event(event(starts = "2026-07-01T10:00", ends = "2026-07-01T12:30"), melbourne)

    assertTrue(ics.contains("DTSTART:20260701T000000Z\r\n"), ics)
    assertTrue(ics.contains("DTEND:20260701T023000Z\r\n"), ics)
  }

  @Test
  fun `an all-day event uses dates`() {
    val ics = IcsWriter.event(event(starts = "2026-11-12T00:00", allDay = true), melbourne)

    assertTrue(ics.contains("DTSTART;VALUE=DATE:20261112\r\n"), ics)
    assertTrue(ics.contains("DTEND;VALUE=DATE:20261113\r\n"), ics)
  }

  @Test
  fun `each notification duration becomes an alarm`() {
    val ics = IcsWriter.event(event(alarms = listOf(Duration.ofDays(1), Duration.ofHours(2))), melbourne)

    assertEquals(2, Regex("BEGIN:VALARM").findAll(ics).count())
    assertTrue(ics.contains("TRIGGER:-P1D\r\n") || ics.contains("TRIGGER:-PT24H\r\n"), ics)
    assertTrue(ics.contains("TRIGGER:-PT2H\r\n"), ics)
    assertTrue(ics.contains("ACTION:DISPLAY\r\n"))
  }

  @Test
  fun `the file is a valid calendar with CRLF lines, escaped text and folded long lines`() {
    val ics = IcsWriter.event(event(title = "Swim; then lunch, maybe", notes = "Line one\nLine two ".repeat(10)),
        melbourne)

    assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
    assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
    assertTrue(ics.contains("VERSION:2.0\r\n") && ics.contains("PRODID:-//hutch.sh//Postmaster//EN\r\n"))
    assertTrue(ics.split("\r\n").all { it.toByteArray().size <= 75 }, "lines folded at 75 octets")
    assertTrue(!ics.replace("\r\n", "").contains("\n"), "no bare LF")
    assertTrue(unfolded(ics).contains("SUMMARY:Swim\\; then lunch\\, maybe\r\n"), unfolded(ics))
    assertTrue(unfolded(ics).contains("Line one\\nLine two"), unfolded(ics))
  }
}
