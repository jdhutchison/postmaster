package sh.hutch.postmaster.actions

import com.fasterxml.jackson.databind.JsonNode
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.PromptContext
import sh.hutch.postmaster.models.PromptEvent
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.EmailEventRepository
import sh.hutch.postmaster.services.LlmClient
import sh.hutch.postmaster.services.PromptRenderer
import sh.hutch.postmaster.services.SettingKeys
import sh.hutch.postmaster.services.SettingsService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Finds events in emails with the LLM and suggests calendars for them. */
@ApplicationScoped
class EventFinder(
    private val llm: LlmClient,
    private val prompts: PromptRenderer,
    private val events: EmailEventRepository,
    private val calendars: CalendarRepository,
    private val settings: SettingsService
) {

  /**
   * Asks the LLM for the email's events (identify_events), unless the summary already found some;
   * returns how many were added.
   */
  fun identify(context: ProcessingContext): Int {
    if (context.eventsFound) {
      return 0
    }
    val answer = llm.chatJson(prompts.render("identify_events", promptContext(context.email)))
    answer.path("errors").forEach { context.addError(ErrorPhase.LLM, "identify_events: ${it.asText()}") }
    return addEvents(context, answer.path("events"))
  }

  /**
   * Stores the LLM's events (the "events" array of the summarise and identify_events prompts),
   * except those the email already has: the same title and start, as when an email is reprocessed
   * and its added events were kept.
   */
  fun addEvents(context: ProcessingContext, array: JsonNode): Int {
    if (!array.isArray) {
      return 0
    }
    val duration = settings.getDuration(SettingKeys.EVENTS_DEFAULT_DURATION)
    var added = 0
    for (item in array) {
      val title = item.path("title").asText("").trim()
      val whenText = item.path("when").asText("").trim()
      if (title.isEmpty() || whenText.isEmpty()) {
        context.addError(ErrorPhase.LLM, "An event without a title or time was left out")
        continue
      }
      val event = EmailEventEntity()
      event.email = context.email
      event.title = title.take(500)
      try {
        if (DATE.matches(whenText)) {
          event.startsAt = LocalDate.parse(whenText).atStartOfDay()
          event.allDay = true
        } else {
          val start = LocalDateTime.parse(whenText.removeSuffix("Z"))
          event.startsAt = start
          event.endsAt = start.plus(duration)
        }
      } catch (e: DateTimeParseException) {
        context.addError(ErrorPhase.LLM, "The event '$title' has a time that can't be read: $whenText")
        continue
      }
      context.eventsFound = true
      if (context.email.events.any { it.title.equals(event.title, ignoreCase = true) && it.startsAt == event.startsAt }) {
        continue
      }
      event.location = item.path("location").takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }?.take(500)
      event.notes = item.path("notes").takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }
      context.email.events.add(event)
      events.persist(event)
      added++
    }
    return added
  }

  /**
   * Adds an all-day event for something to be done on or by [day] (CLARIFICATIONS.md R9), unless the
   * email already has an event that day with the same title, or one containing the other; returns
   * whether it was added.
   */
  fun addDated(context: ProcessingContext, title: String, day: LocalDate, notes: String?): Boolean {
    val text = title.trim().take(500)
    val same = context.email.events.any { event ->
      event.startsAt?.toLocalDate() == day &&
          (event.title.contains(text, ignoreCase = true) || text.contains(event.title, ignoreCase = true))
    }
    if (text.isEmpty() || same) {
      return false
    }
    val event = EmailEventEntity()
    event.email = context.email
    event.title = text
    event.startsAt = day.atStartOfDay()
    event.allDay = true
    event.notes = notes?.trim()?.ifEmpty { null }
    context.email.events.add(event)
    events.persist(event)
    context.eventsFound = true
    return true
  }

  /** Suggests a calendar for each event without one (select_calendar); the map's names must be enabled calendars. */
  fun suggestCalendars(context: ProcessingContext, targets: List<EmailEventEntity>) {
    val enabled = calendars.listEnabled()
    val unassigned = targets.filter { it.calendar == null }
    if (enabled.isEmpty() || unassigned.isEmpty()) {
      return
    }
    events.flush()
    val promptEvents = unassigned.map {
      PromptEvent(it.id!!, it.title, it.startsAt.toString(), it.location, it.notes)
    }
    val answer = llm.chatJson(prompts.render("select_calendar", promptContext(context.email).copy(events = promptEvents)))
    for (event in unassigned) {
      val name = answer.path(event.id.toString()).asText("").trim()
      if (name.isNotEmpty()) {
        event.calendar = byName(enabled, name)
            ?: null.also { context.addError(ErrorPhase.LLM, "select_calendar chose '$name', which isn't an enabled calendar") }
      }
    }
  }

  companion object {
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun byName(calendars: List<CalendarEntity>, name: String): CalendarEntity? =
      calendars.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The prompt values for an email. */
    fun promptContext(email: EmailEntity): PromptContext =
      PromptContext(
          subject = email.subject,
          sender = listOfNotNull(email.senderName, email.sender?.let { "<$it>" }).joinToString(" "),
          text = email.message,
          html = email.htmlMessage,
          page = null,
          receivedAt = email.receivedAt
      )
  }
}
