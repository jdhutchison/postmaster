package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sh.hutch.postmaster.models.CalendarEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * The .ics files are accepted by the real khal (REQ create_events "using
 * Khal"; CLAR D18) and land at the right local time. Runs only where khal is
 * installed (it is on the development machine and in the container).
 */
class RealKhalTest {
  @TempDir
  lateinit var dir: Path

  private val melbourne = ZoneId.of("Australia/Melbourne")

  @Test
  fun `khal imports a timed event with alarms at the right local time`() {
    val config = khalConfig()
    val ics = dir.resolve("event.ics")
    ics.writeText(
        IcsWriter.event(
            CalendarEvent(
                "real-khal-1", "Zoo excursion, grade 3", LocalDateTime.parse("2026-11-05T09:00"), null, false,
                "Melbourne Zoo", "Bring lunch", listOf(Duration.ofDays(1), Duration.ofHours(2))
            ),
            melbourne
        )
    )

    assertEquals(0, khal(config, "import", "--batch", "-a", "Family", ics.toString()).first)

    val (code, listing) = khal(config, "list", "--format", "{start-time} {title}", "05.11.2026", "1d")
    assertEquals(0, code, listing)
    assertTrue(listing.contains("09:00 Zoo excursion, grade 3"), listing)
    assertTrue(Files.list(dir.resolve("family")).use { files -> files.anyMatch { it.toString().endsWith(".ics") } })
  }

  @Test
  fun `khal imports an all-day event on its date`() {
    val config = khalConfig()
    val ics = dir.resolve("allday.ics")
    ics.writeText(
        IcsWriter.event(
            CalendarEvent("real-khal-2", "Interviews", LocalDateTime.parse("2026-11-12T00:00"), null, true, null, null, emptyList()),
            melbourne
        )
    )

    assertEquals(0, khal(config, "import", "--batch", "-a", "Family", ics.toString()).first)

    val (_, listing) = khal(config, "list", "--format", "{title}", "12.11.2026", "1d")
    assertTrue(listing.contains("Interviews"), listing)
    val (_, next) = khal(config, "list", "--format", "{title}", "13.11.2026", "1d")
    assertTrue(!next.contains("Interviews"), "one day only: $next")
  }

  private fun khalConfig(): Path {
    val khal = listOf("/usr/bin/khal", "/usr/local/bin/khal").firstOrNull { Files.isExecutable(Path.of(it)) }
    assumeTrue(khal != null, "khal is not installed")
    Files.createDirectories(dir.resolve("family"))
    val config = dir.resolve("khal.ini")
    config.writeText(
        """
        |[calendars]
        |[[Family]]
        |path = ${dir.resolve("family")}
        |
        |[locale]
        |local_timezone = Australia/Melbourne
        |default_timezone = Australia/Melbourne
        |timeformat = %H:%M
        |dateformat = %d.%m.%Y
        |longdateformat = %d.%m.%Y
        |datetimeformat = %d.%m.%Y %H:%M
        |longdatetimeformat = %d.%m.%Y %H:%M
        |
        |[sqlite]
        |path = ${dir.resolve("khal.db")}
        |""".trimMargin()
    )
    return config
  }

  private fun khal(config: Path, vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("khal", "-c", config.toString()) + args)
        .redirectErrorStream(true)
        .apply { environment()["HOME"] = dir.toString() }
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(30, TimeUnit.SECONDS)
    return process.exitValue() to output
  }
}
