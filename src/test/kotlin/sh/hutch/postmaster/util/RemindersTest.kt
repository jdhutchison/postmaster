package sh.hutch.postmaster.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Notifications: durations before the event, or a time of day days before its day (CLAR R9)
class RemindersTest {

  @Test
  fun `durations are reminders before the event, as before`() {
    assertEquals(Reminder.Before(Duration.ofHours(4)), Reminders.parse("4h"))
    assertEquals(Reminder.Before(Duration.ofMinutes(30)), Reminders.parse("PT30M"))
  }

  @Test
  fun `a time of day takes 24-hour ISO 8601 times or 12-hour times`() {
    assertEquals(Reminder.At(1, LocalTime.of(20, 0)), Reminders.parse("d-1@20:00"))
    assertEquals(Reminder.At(1, LocalTime.of(20, 0)), Reminders.parse("d-1@8pm"))
    assertEquals(Reminder.At(0, LocalTime.of(8, 0)), Reminders.parse("d@8am"))
    assertEquals(Reminder.At(0, LocalTime.of(9, 5)), Reminders.parse("d@09:05"))
    assertEquals(Reminder.At(2, LocalTime.of(20, 30)), Reminders.parse("D-2@8:30PM"))
    assertEquals(Reminder.At(0, LocalTime.of(0, 0)), Reminders.parse("d@12am"))
    assertEquals(Reminder.At(0, LocalTime.of(12, 15)), Reminders.parse("d@12:15pm"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["d-1@20", "d@25:00", "d@8:60am", "d@13pm", "d@0am", "d+1@8am", "d-1", "x-1@8am", "d-1@", "tomorrow"])
  fun `other text isn't a notification`(text: String) {
    assertThrows<InvalidDurationException> { Reminders.parse(text) }
    assertFalse(Reminders.isValid(text))
  }

  @Test
  fun `lists mix both, separated by a comma and a space`() {
    assertEquals(
        listOf(Reminder.Before(Duration.ofHours(1)), Reminder.At(1, LocalTime.of(20, 0)), Reminder.At(0, LocalTime.of(8, 0))),
        Reminders.parseList("1h, d-1@20:00, d@8am")
    )
    assertTrue(Reminders.isValidList("4h, 1d"))
    assertFalse(Reminders.isValidList("1h,d-1@20:00"))
    assertFalse(Reminders.isValidList(""))
    assertEquals(emptyList<Reminder>(), Reminders.parseListOrEmpty("not a list"))
    assertEquals(emptyList<Reminder>(), Reminders.parseListOrEmpty(null))
  }

  @Test
  fun `a time of day goes off that many days before the event's day`() {
    val day = LocalDate.of(2026, 11, 5)

    assertEquals(LocalDateTime.of(2026, 11, 4, 20, 0), Reminder.At(1, LocalTime.of(20, 0)).on(day))
    assertEquals(LocalDateTime.of(2026, 11, 5, 8, 0), Reminder.At(0, LocalTime.of(8, 0)).on(day))
  }
}
