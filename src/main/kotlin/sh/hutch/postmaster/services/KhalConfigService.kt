package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.config.PostmasterConfig
import sh.hutch.postmaster.models.KhalCalendar
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

  /** The calendars with their vdir directories; an empty list if the file can't be read. */
  fun calendarPaths(): List<KhalCalendar> {
    val lines = try {
      Files.readAllLines(Path.of(config.khalConfig()))
    } catch (e: IOException) {
      return emptyList()
    } catch (e: InvalidPathException) {
      return emptyList()
    }
    return calendarEntries(lines).mapNotNull { (name, path) -> path?.let { KhalCalendar(name, expand(it)) } }
  }

  companion object {
    private val KEY_VALUE = Regex("""\s*([A-Za-z_]+)\s*=\s*(.*?)\s*""")

    /** Each calendar's name and its `path` value, as written. */
    fun calendarEntries(lines: List<String>): List<Pair<String, String?>> {
      val entries = mutableListOf<Pair<String, String?>>()
      var inCalendars = false
      var current: String? = null
      val paths = mutableMapOf<String, String>()
      for (raw in lines) {
        val line = raw.substringBefore('#')
        val subsection = SUBSECTION.matchEntire(line)
        val section = SECTION.matchEntire(line)
        val keyValue = KEY_VALUE.matchEntire(line)
        when {
          subsection != null -> {
            current = if (inCalendars) subsection.groupValues[1].trim() else null
            current?.let { name -> if (entries.none { it.first == name }) entries += name to null }
          }
          section != null -> {
            inCalendars = section.groupValues[1].trim() == "calendars"
            current = null
          }
          keyValue != null && current != null && keyValue.groupValues[1] == "path" ->
            paths[current!!] = keyValue.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
        }
      }
      return entries.filter { it.first.isNotEmpty() }.map { (name, _) -> name to paths[name] }
    }

    /** The path with a leading `~` expanded to the user's home directory. */
    fun expand(path: String): Path =
      if (path == "~" || path.startsWith("~/")) Path.of(System.getProperty("user.home") + path.substring(1)) else Path.of(path)

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
