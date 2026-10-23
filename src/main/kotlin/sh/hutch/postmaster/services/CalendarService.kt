package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.models.CalendarInput
import sh.hutch.postmaster.models.KhalCalendars
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.util.Colours
import sh.hutch.postmaster.util.IsoDurations

/** Manages the calendars events can be added to. Names must be calendars in the khal configuration. */
@ApplicationScoped
@Transactional
class CalendarService(private val calendars: CalendarRepository, private val khal: KhalConfigService) {

  fun list(): List<CalendarEntity> = calendars.listActive()

  fun get(id: Long): CalendarEntity = calendars.findActive(id) ?: throw NotFoundException("Calendar $id not found")

  fun khalCalendars(): KhalCalendars = khal.calendars()

  fun create(input: CalendarInput): CalendarEntity {
    val calendar = CalendarEntity()
    apply(calendar, input)
    calendars.persist(calendar)
    return calendar
  }

  fun update(id: Long, input: CalendarInput): CalendarEntity {
    val calendar = get(id)
    apply(calendar, input)
    return calendar
  }

  fun delete(id: Long): CalendarEntity {
    val calendar = get(id)
    calendar.markDeleted()
    return calendar
  }

  fun setEnabled(id: Long, enabled: Boolean): CalendarEntity {
    val calendar = get(id)
    calendar.enabled = enabled
    return calendar
  }

  private fun apply(calendar: CalendarEntity, input: CalendarInput) {
    val errors = Errors()
    val name = errors.required("name", input.name, "Calendar", MAX_NAME)
    if (name != null) {
      val known = khal.calendars()
      if (name != calendar.name && name !in known.names) {
        errors.add("name", known.error ?: "'$name' is not a calendar in the khal configuration")
      } else {
        val other = calendars.findActiveByName(name)
        if (other != null && other.id != calendar.id) {
          errors.duplicate("name", "A calendar named '${other.name}' already exists")
        }
      }
    }
    val description = errors.required("description", input.description, "Description")
    val colour = Colours.normalise(input.colour)
    if (colour == null) {
      errors.add("colour", "Colour must be an RGB hex code such as #ff5555")
    }
    val periods = input.notificationPeriods?.trim()?.ifEmpty { null }
    if (periods != null && !IsoDurations.isValidList(periods)) {
      errors.add(
          "notificationPeriods",
          "Durations must be a list of ISO 8601 durations separated by a comma and a space, such as 4h, 1d"
      )
    }
    errors.throwIfAny()
    calendar.name = name!!
    calendar.description = description!!
    calendar.colour = colour!!
    calendar.enabled = input.enabled
    calendar.notificationPeriods = periods
  }

  companion object {
    const val MAX_NAME = 100
  }
}
