package sh.hutch.postmaster.config

import io.agroal.api.AgroalDataSource
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.EmailRepository
import sh.hutch.postmaster.repositories.ProcessingRunRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.services.SettingsService
import sh.hutch.postmaster.services.TodoListService
import sh.hutch.postmaster.testsupport.getHtml
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.rows
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fake data for dev mode (CLAR R2): every page has something to show,
 * with email in every state. DevData is only a bean in the dev build, so the
 * test makes one, in an application with a database of its own (its rules
 * would change what the other tests' email does).
 */
@QuarkusTest
@TestProfile(DevDataTest.OwnDatabase::class)
class DevDataTest {
  class OwnDatabase : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.datasource.jdbc.url" to "jdbc:h2:mem:devdata;MODE=MariaDB;DATABASE_TO_UPPER=FALSE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
    )
  }

  @Inject lateinit var config: PostmasterConfig
  @Inject lateinit var settings: SettingsService
  @Inject lateinit var types: TypeRepository
  @Inject lateinit var calendars: CalendarRepository
  @Inject lateinit var rules: RuleRepository
  @Inject lateinit var emails: EmailRepository
  @Inject lateinit var runs: ProcessingRunRepository
  @Inject lateinit var todos: TodoListService
  @Inject lateinit var dataSource: AgroalDataSource

  private fun devData(): DevData {
    val khal = dir.resolve("khal.ini").toString()
    val devConfig = object : PostmasterConfig by config {
      override fun khalConfig() = khal
    }
    return DevData(devConfig, settings, types, calendars, rules, emails, runs, todos)
  }

  @BeforeEach
  fun seedOnce() {
    if (!seeded) {
      dir = Files.createTempDirectory("postmaster-dev")
      devData().onStart(StartupEvent())
      seeded = true
    }
  }

  private fun count(sql: String): Long = (dataSource.row(sql)["n"] as Number).toLong()

  @Test
  fun `about sixty emails are seeded, in every state`() {
    assertEquals(57L, count("SELECT COUNT(*) AS n FROM Emails"))
    val statuses = dataSource.rows("SELECT DISTINCT status FROM Emails").map { it["status"] }.toSet()
    assertEquals(setOf("processed", "skipped", "failed", "pending"), statuses)
    assertTrue(count("SELECT COUNT(*) AS n FROM Emails WHERE type_id IS NULL") > 0, "some unclassified")
    assertTrue(count("SELECT COUNT(*) AS n FROM Emails WHERE response_required = TRUE") > 0, "some need a reply")
    assertTrue(count("SELECT COUNT(*) AS n FROM Emails WHERE action_required = TRUE") > 0, "some need action")
    assertTrue(count("SELECT COUNT(*) AS n FROM Email_errors") > 0, "some have errors")
    assertTrue(count("SELECT COUNT(*) AS n FROM Email_events WHERE is_added = TRUE") > 0, "events added")
    assertTrue(count("SELECT COUNT(*) AS n FROM Email_events WHERE is_added = FALSE") > 0, "events suggested")
    assertTrue(count("SELECT COUNT(*) AS n FROM Email_action_results") > 0, "actions recorded")
  }

  @Test
  fun `calendars, rules and processing runs are seeded`() {
    assertTrue(count("SELECT COUNT(*) AS n FROM Calendars WHERE enabled = FALSE") > 0, "a disabled calendar")
    assertTrue(count("SELECT COUNT(*) AS n FROM Rules WHERE rule_type = 'classification'") > 0)
    assertTrue(count("SELECT COUNT(*) AS n FROM Rules WHERE rule_type = 'action'") > 0)
    val runStatuses = dataSource.rows("SELECT DISTINCT status FROM Processing_runs").map { it["status"] }.toSet()
    assertTrue(runStatuses.containsAll(setOf("succeeded", "failed")), "run statuses: $runStatuses")
  }

  @Test
  fun `the khal config, attachments and to-do list are written`() {
    assertTrue(Files.isRegularFile(dir.resolve("khal.ini")))
    val stored = dataSource.rows("SELECT stored_path FROM Email_attachments WHERE stored_path IS NOT NULL").map { it["stored_path"] as String }
    assertTrue(stored.isNotEmpty())
    for (path in stored) {
      assertTrue(Files.isRegularFile(dir.resolve("attachments").resolve(path)), "attachment $path")
    }
    val index = Files.readString(dir.resolve("todo/index.md"))
    val names = dataSource.rows("SELECT todo_name FROM Email_action_items WHERE todo_name IS NOT NULL").map { it["todo_name"] as String }
    assertEquals(3, names.size)
    for (name in names) {
      assertTrue(index.contains("- [ ] [[$name]] #todo-item"), index)
      assertTrue(Files.isRegularFile(dir.resolve("todo/$name.md")), name)
    }
  }

  @Test
  fun `every page shows the fake data`() {
    for (path in listOf("/", "/emails", "/types", "/calendars", "/prompts", "/rules/classification", "/rules/action", "/configuration")) {
      getHtml(path)
    }
    for (id in dataSource.rows("SELECT id FROM Emails").map { it["id"] }) {
      getHtml("/emails/$id")
    }
  }

  @Test
  fun `starting again doesn't seed twice`() {
    devData().onStart(StartupEvent())

    assertEquals(57L, count("SELECT COUNT(*) AS n FROM Emails"))
  }

  companion object {
    private var seeded = false
    private lateinit var dir: Path
  }
}
