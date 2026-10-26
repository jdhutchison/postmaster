package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.services.CalendarClient
import sh.hutch.postmaster.util.Reminders

/** Finds the email's events and suggests calendars, without adding them (CLARIFICATIONS.md Q11). */
@ApplicationScoped
class CheckForEventsHandler(private val finder: EventFinder) : ActionHandler {
  override val actionType = ActionTypes.CHECK_FOR_EVENTS
  override val stage = ActionStage.GATHER
  override val order = 20

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val found = finder.identify(context)
    finder.suggestCalendars(context, context.email.events.filter { !it.isAdded })
    val count = context.email.events.size
    return if (count == 0) ActionOutcome.done("No events found") else ActionOutcome.done("$count event(s) found, $found new")
  }
}

/** Adds the email's events to calendars with khal, finding them first if needed. */
@ApplicationScoped
class CreateEventsHandler(
    private val finder: EventFinder,
    private val calendars: CalendarRepository,
    private val calendar: CalendarClient
) : ActionHandler {
  override val actionType = ActionTypes.CREATE_EVENTS
  override val stage = ActionStage.GATHER
  override val order = 40

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    finder.identify(context)
    val pending = context.email.events.filter { !it.isAdded }
    if (pending.isEmpty()) {
      return ActionOutcome.skipped("No events to add")
    }
    val fixed = action.calendar?.takeIf { !it.deleted }
    if (fixed == null) {
      finder.suggestCalendars(context, pending)
    }
    val added = mutableListOf<String>()
    val left = mutableListOf<String>()
    for (event in pending) {
      val target = fixed ?: event.calendar?.takeIf { !it.deleted && it.enabled }
      if (target == null) {
        left += event.title
        continue
      }
      val alarms = Reminders.parseListOrEmpty(action.notifications ?: target.notificationPeriods)
      val uid = calendar.addEvent(
          target.name,
          CalendarEvent(CalendarClient.eventUid(context.email.id!!, event.id!!), event.title, event.startsAt!!, event.endsAt, event.allDay, event.location, event.notes, alarms)
      )
      event.calendar = target
      event.isAdded = true
      event.khalUid = uid
      added += "${event.title} (${target.name})"
    }
    val detail = buildString {
      if (added.isNotEmpty()) append("Added ${added.joinToString(", ")}")
      if (left.isNotEmpty()) {
        if (isNotEmpty()) append("; ")
        append("no calendar for ${left.joinToString(", ")}")
      }
    }
    return if (added.isEmpty()) ActionOutcome.failed(detail) else ActionOutcome.done(detail)
  }
}
