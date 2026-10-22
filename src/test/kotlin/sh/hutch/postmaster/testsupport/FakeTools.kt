package sh.hutch.postmaster.testsupport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The fake khal and Chromium executables and the temporary khal setup they
 * work against. KhalTestResource creates the setup before Quarkus starts; its
 * location is shared through a system property so tests in any class loader
 * can find it.
 *
 * Layout under the root directory:
 * - `khal.ini`: khal config listing [CALENDARS]
 * - `calendars/<dir>/`: each calendar's vdir
 * - `khal/khal`, `chromium/chromium`: the fake executables, each with a
 *   `calls/` directory recording invocations and an optional `fail` file
 */
object FakeTools {
  const val ROOT_PROPERTY = "postmastertest.fake-root"

  /** Calendar names in the test khal config, mapped to their vdir directory names */
  val CALENDARS: Map<String, String> = linkedMapOf(
      "Family-and-Kids" to "family-and-kids",
      "Kids medical" to "kids-medical",
      "Football" to "football",
      "Bills" to "bills",
      "Dinners" to "dinners"
  )

  fun root(): Path =
      Path.of(System.getProperty(ROOT_PROPERTY) ?: error("KhalTestResource has not created the fake tools"))

  fun khalConfig(): Path = root().resolve("khal.ini")

  fun khalCommand(): Path = root().resolve("khal/khal")

  fun chromiumCommand(): Path = root().resolve("chromium/chromium")

  fun calendarDir(name: String): Path =
      root().resolve("calendars").resolve(CALENDARS[name] ?: error("No test calendar named $name"))

  /** The .ics files currently in a calendar's vdir */
  fun eventFiles(calendar: String): List<Path> {
    val dir = calendarDir(calendar)
    return if (dir.exists()) dir.listDirectoryEntries("*.ics").sorted() else emptyList()
  }

  fun khalCalls(): List<FakeCall> = calls(root().resolve("khal/calls"))

  fun chromiumCalls(): List<FakeCall> = calls(root().resolve("chromium/calls"))

  /** Makes every later khal call fail with [message] on stderr */
  fun failKhal(message: String) {
    root().resolve("khal/fail").writeText(message)
  }

  /** Makes every later Chromium call fail with [message] on stderr */
  fun failChromium(message: String) {
    root().resolve("chromium/fail").writeText(message)
  }

  /** Clears recorded calls, failure switches and calendar contents */
  fun reset() {
    for (tool in listOf("khal", "chromium")) {
      Files.deleteIfExists(root().resolve("$tool/fail"))
      val calls = root().resolve("$tool/calls")
      if (calls.exists()) {
        calls.toFile().deleteRecursively()
      }
    }
    for (dir in CALENDARS.values) {
      val calendar = root().resolve("calendars").resolve(dir)
      calendar.toFile().deleteRecursively()
      Files.createDirectories(calendar)
    }
  }

  internal fun create(): Path {
    val root = Files.createTempDirectory("postmaster-test-")
    for (tool in listOf("khal", "chromium")) {
      val target = root.resolve(tool).resolve(tool)
      Files.createDirectories(target.parent)
      val script = FakeTools::class.java.getResourceAsStream("/fakes/$tool")
          ?: error("Missing test resource /fakes/$tool")
      script.use { Files.copy(it, target) }
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    val config = StringBuilder("[calendars]\n")
    for ((name, dir) in CALENDARS) {
      val path = root.resolve("calendars").resolve(dir)
      Files.createDirectories(path)
      config.append("\n[[$name]]\npath = \"$path\"\ncolor = \"light green\"\n")
    }
    config.append(
        """
        |
        |[locale]
        |local_timezone = "Australia/Melbourne"
        |default_timezone = "Australia/Melbourne"
        |
        |[sqlite]
        |path = "${root.resolve("khal.db")}"
        |""".trimMargin()
    )
    root.resolve("khal.ini").writeText(config.toString())
    System.setProperty(ROOT_PROPERTY, root.toString())
    return root
  }

  internal fun delete() {
    System.getProperty(ROOT_PROPERTY)?.let { Path.of(it).toFile().deleteRecursively() }
    System.clearProperty(ROOT_PROPERTY)
  }

  private fun calls(dir: Path): List<FakeCall> {
    if (!dir.exists()) {
      return emptyList()
    }
    return dir.listDirectoryEntries("*.args")
        .sortedBy { it.name }
        .map { argsFile ->
          val raw = String(argsFile.readBytes(), Charsets.UTF_8)
          val args = if (raw.isEmpty()) emptyList() else raw.removeSuffix("\u0000").split('\u0000')
          val ics = argsFile.resolveSibling(argsFile.name.removeSuffix(".args") + ".ics")
          FakeCall(args, if (ics.isRegularFile()) ics.readText() else null)
        }
  }
}

/** One invocation of a fake executable: its arguments and, for khal import, the imported file */
data class FakeCall(val args: List<String>, val importedIcs: String?)
