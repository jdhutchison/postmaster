package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.models.KhalCalendars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Reads the calendar names from the khal configuration file (`postmaster.khal-config`). */
@ApplicationScoped
class KhalConfigService(private val config: PostmasterConfig) {

  fun calendars(): KhalCalendars {
    val path = try {
      Path.of(config.khalConfig())
    } catch (e: InvalidPathException) {
      return KhalCalendars(emptyList(), "The khal configuration path '${config.khalConfig()}' is not valid")
    }
    val lines = try {
      Files.readAllLines(path)
    } catch (e: IOException) {
      return KhalCalendars(emptyList(), "The khal configuration '$path' could not be read")
    }
    val names = calendarNames(lines)
    return KhalCalendars(names, if (names.isEmpty()) "The khal configuration '$path' lists no calendars" else null)
  }

  companion object {
    private val SECTION = Regex("""\s*\[([^\[\]]+)]\s*""")
    private val SUBSECTION = Regex("""\s*\[\[([^\[\]]+)]]\s*""")

    /** The `[[name]]` subsections of the `[calendars]` section, in file order. */
    fun calendarNames(lines: List<String>): List<String> {
      val names = mutableListOf<String>()
      var inCalendars = false
      for (raw in lines) {
        val line = raw.substringBefore('#')
        val subsection = SUBSECTION.matchEntire(line)
        if (subsection != null) {
          if (inCalendars) {
            names += subsection.groupValues[1].trim()
          }
          continue
        }
        val section = SECTION.matchEntire(line)
        if (section != null) {
          inCalendars = section.groupValues[1].trim() == "calendars"
        }
      }
      return names.filter { it.isNotEmpty() }.distinct()
    }
  }
}
