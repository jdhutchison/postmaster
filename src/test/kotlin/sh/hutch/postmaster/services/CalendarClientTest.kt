package sh.hutch.postmaster.services

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.IntegrationProfile
import sh.hutch.postmaster.util.Reminder
import java.time.Duration
import java.time.LocalDateTime
import kotlin.io.path.readText

/**
 * Adding, moving and deleting events with khal (REQ create_events "using
 * Khal", notifications; Viewing an individual email 3 (move an event between
 * calendars); CLAR D8, D18), with the fake khal.
 */
@QuarkusTest
@TestProfile(IntegrationProfile::class)
class CalendarClientTest {
  @Inject
  lateinit var calendar: CalendarClient

  @BeforeEach
  fun reset() {
    FakeTools.reset()
  }

  private fun event(uid: String? = null, allDay: Boolean = false, alarms: List<Duration> = emptyList()) = CalendarEvent(
      uid = uid,
      title = "Zoo excursion",
      startsAt = LocalDateTime.parse("2026-11-05T09:00"),
      endsAt = null,
      allDay = allDay,
      location = "Melbourne Zoo",
      notes = "Bring lunch",
      alarms = alarms.map { Reminder.Before(it) }
  )

  @Test
  fun `the calendars and their directories come from the khal config`() {
    val calendars = calendar.calendars()

    assertEquals(FakeTools.CALENDARS.keys.toList(), calendars.map { it.name })
    assertEquals(FakeTools.calendarDir("Kids medical"), calendars.single { it.name == "Kids medical" }.path)
  }

  @Test
  fun `an event is imported into the chosen calendar with khal`() {
    val uid = calendar.addEvent("Kids medical", event(uid = "test-uid-1"))

    assertEquals("test-uid-1", uid)
    val call = FakeTools.khalCalls().single()
    val args = call.args
    assertEquals(listOf("-c", FakeTools.khalConfig().toString(), "import", "--batch", "-a", "Kids medical"),
        args.dropLast(1))
    assertTrue(call.importedIcs!!.contains("UID:test-uid-1"))
    assertEquals(1, FakeTools.eventFiles("Kids medical").size)
  }

  @Test
  fun `an event whose UID is already in the calendar isn't added again`() {
    // CLAR R6: create_events can run again after an interruption without duplicating events
    calendar.addEvent("Kids medical", event(uid = "pm-1-2"))

    assertEquals("pm-1-2", calendar.addEvent("Kids medical", event(uid = "pm-1-2")))

    assertEquals(1, FakeTools.khalCalls().size, "khal was asked once")
    assertEquals(1, FakeTools.eventFiles("Kids medical").size)
  }

  @Test
  fun `a new event gets its own UID`() {
    val uid = calendar.addEvent("Football", event())

    assertTrue(uid.startsWith("pm-"), uid)
    assertTrue(FakeTools.khalCalls().single().importedIcs!!.contains("UID:$uid"))
  }

  @Test
  fun `a khal failure is reported with khal's output`() {
    FakeTools.failKhal("error: calendar is read-only")

    val error = assertThrows<CalendarException> { calendar.addEvent("Football", event()) }
    assertTrue(error.message!!.contains("read-only"), error.message)
  }

  @Test
  fun `an event is moved to another calendar by moving its file`() {
    // REQ Viewing an individual email 3; CLAR D18
    val uid = calendar.addEvent("Family-and-Kids", event())

    calendar.moveEvent(uid, "Family-and-Kids", "Kids medical")

    assertTrue(FakeTools.eventFiles("Family-and-Kids").isEmpty())
    assertTrue(FakeTools.eventFiles("Kids medical").single().readText().contains("UID:$uid"))
  }

  @Test
  fun `an event is deleted by deleting its file`() {
    val uid = calendar.addEvent("Bills", event())

    calendar.deleteEvent(uid, "Bills")

    assertTrue(FakeTools.eventFiles("Bills").isEmpty())
  }

  @Test
  fun `a missing event or an unknown calendar is an error`() {
    assertThrows<CalendarException> { calendar.deleteEvent("no-such-uid", "Bills") }
    assertThrows<CalendarException> { calendar.moveEvent("no-such-uid", "Bills", "Football") }
    assertThrows<CalendarException> { calendar.addEvent("Not a calendar", event()) }
  }
}
