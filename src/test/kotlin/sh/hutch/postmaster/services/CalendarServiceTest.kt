package sh.hutch.postmaster.services

import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.CalendarInput
import sh.hutch.postmaster.testsupport.FakeTools

// REQ Calendars 3-7 and Calendars schema; CLAR D3 (soft delete), D4 (names
// from the khal config), D20 (names unique ignoring case)
@QuarkusTest
class CalendarServiceTest {
  @Inject
  lateinit var service: CalendarService

  /** Frees the khal calendar names: tests outside a rolled-back transaction keep what they create */
  @AfterEach
  fun deleteCalendars() {
    service.list().forEach { service.delete(it.id!!) }
  }

  private fun input(name: String?, description: String? = "Family events", periods: String? = null) =
      CalendarInput(name, description, "#50fa7b", true, periods)

  @Test
  fun `the khal calendars are read from the khal config in order`() {
    assertEquals(FakeTools.CALENDARS.keys.toList(), service.khalCalendars().names)
    assertNull(service.khalCalendars().error)
  }

  @Test
  fun `a calendar needs a khal calendar name, a description and a colour`() {
    // REQ Calendars 5: all fields are required except Durations; CLAR D4
    val calendar = service.create(input("Family-and-Kids"))
    assertEquals("Family-and-Kids", calendar.name)
    assertTrue(calendar.enabled)

    assertTrue("name" in assertThrows<ValidationException> { service.create(input("Not in khal")) }.errors)
    assertTrue("description" in assertThrows<ValidationException> { service.create(input("Football", " ")) }.errors)
    val noColour = CalendarInput("Football", "Games", null, true, null)
    assertTrue("colour" in assertThrows<ValidationException> { service.create(noColour) }.errors)
  }

  @Test
  fun `notification durations must be a comma plus space separated list`() {
    // REQ Calendars 7
    assertEquals("4h, 1d", service.create(input("Kids medical", periods = "4h, 1d")).notificationPeriods)
    assertNull(service.create(input("Football", periods = " ")).notificationPeriods)
    for (periods in listOf("4h,1d", "4h ,1d", "4 hours", "1y")) {
      val error = assertThrows<ValidationException> { service.create(input("Bills", periods = periods)) }
      assertTrue("notificationPeriods" in error.errors, "$periods rejected")
    }
  }

  @Test
  fun `names that differ only in case are rejected`() {
    // CLAR D20 (khal's own names are case-sensitive, so this also stops near-duplicates)
    service.create(input("Dinners"))

    assertEquals("name", assertThrows<DuplicateNameException> { service.create(input("Dinners")) }.field)
  }

  @Test
  @TestTransaction
  fun `calendars can be disabled and enabled`() {
    // REQ Calendars 3
    val calendar = service.create(input("Bills"))

    assertFalse(service.setEnabled(calendar.id!!, false).enabled)
    assertTrue(service.setEnabled(calendar.id!!, true).enabled)
  }

  @Test
  @TestTransaction
  fun `a deleted calendar is gone from the list but kept in the database`() {
    // CLAR D3
    val calendar = service.create(input("Football"))

    service.delete(calendar.id!!)

    assertTrue(calendar.deleted)
    assertTrue(service.list().none { it.id == calendar.id })
    service.create(input("Football"))
  }

  @Test
  @TestTransaction
  fun `editing keeps the calendar's current name valid`() {
    val calendar = service.create(input("Kids medical"))

    assertEquals("New description", service.update(calendar.id!!, input("Kids medical", "New description")).description)
  }
}
