package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.models.CalendarEvent
import sh.hutch.postmaster.models.KhalCalendar
import sh.hutch.postmaster.util.Processes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Adds events to khal calendars with `khal import`. khal has no command to move or delete an event
 * without asking, so those work on the event's file in the calendar's vdir directory, found by its
 * UID (CLARIFICATIONS.md D18).
 */
@ApplicationScoped
class CalendarClient(
    private val config: PostmasterConfig,
    private val khal: KhalConfigService,
    private val settings: SettingsService
) {

  fun calendars(): List<KhalCalendar> = khal.calendarPaths()

  /** Adds [event] to [calendar] and returns its UID. */
  fun addEvent(calendar: String, event: CalendarEvent): String {
    calendar(calendar)
    val uid = event.uid ?: "pm-${UUID.randomUUID()}"
    val ics = IcsWriter.event(event.copy(uid = uid), settings.getZoneId(SettingKeys.PROCESSING_TIMEZONE))
    val file = Files.createTempFile("postmaster-event-", ".ics")
    try {
      Files.writeString(file, ics)
      val command = listOf(config.khalCommand(), "-c", config.khalConfig(), "import", "--batch", "-a", calendar, file.toString())
      val result = try {
        Processes.run(command, KHAL_TIMEOUT)
      } catch (e: IOException) {
        throw CalendarException("khal (${config.khalCommand()}) could not be started: ${e.message}", e)
      }
      if (result.timedOut || result.exitCode != 0) {
        val output = (result.stderr + "\n" + result.stdout).trim().lines().lastOrNull().orEmpty().take(300)
        throw CalendarException("khal could not add '${event.title}' to $calendar: ${if (result.timedOut) "it took too long" else output}")
      }
    } finally {
      Files.deleteIfExists(file)
    }
    return uid
  }

  /** Moves the event with [uid] from one calendar to another. */
  fun moveEvent(uid: String, from: String, to: String) {
    val source = eventFile(uid, calendar(from))
    val target = calendar(to).path
    try {
      Files.createDirectories(target)
      Files.move(source, target.resolve(source.name), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
      throw CalendarException("The event could not be moved from $from to $to: ${e.message}", e)
    }
  }

  /** Deletes the event with [uid] from [calendar]. */
  fun deleteEvent(uid: String, calendar: String) {
    val file = eventFile(uid, calendar(calendar))
    try {
      Files.delete(file)
    } catch (e: IOException) {
      throw CalendarException("The event could not be deleted from $calendar: ${e.message}", e)
    }
  }

  private fun calendar(name: String): KhalCalendar =
    calendars().firstOrNull { it.name == name } ?: throw CalendarException("There is no khal calendar named '$name'")

  private fun eventFile(uid: String, calendar: KhalCalendar): Path {
    val line = "UID:" + IcsWriter.escape(uid)
    val files = try {
      Files.list(calendar.path).use { stream -> stream.filter { it.name.endsWith(".ics") }.toList() }
    } catch (e: IOException) {
      throw CalendarException("The ${calendar.name} calendar's directory ${calendar.path} could not be read", e)
    }
    return files.firstOrNull { file -> runCatching { file.readText().lines().any { it.trim() == line } }.getOrDefault(false) }
        ?: throw CalendarException("There is no event $uid in the ${calendar.name} calendar")
  }

  companion object {
    private val KHAL_TIMEOUT = Duration.ofSeconds(60)
  }
}
