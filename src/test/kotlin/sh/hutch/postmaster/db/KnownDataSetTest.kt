package sh.hutch.postmaster.db

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.testsupport.FakeTools
import sh.hutch.postmaster.testsupport.KnownDataProfile
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.rows

/**
 * The known data set (PLAN 1.5) loads on top of the seeds and has the shape
 * later tests depend on.
 */
@QuarkusTest
@TestProfile(KnownDataProfile::class)
class KnownDataSetTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Test
  fun `the seeds are still there`() {
    assertEquals(9L, count("SELECT COUNT(*) AS n FROM Types WHERE deleted = FALSE"))
    assertEquals(6L, count("SELECT COUNT(*) AS n FROM Prompts WHERE prompt_key IS NOT NULL"))
  }

  @Test
  fun `configured calendars are named after calendars in the test khal config`() {
    // CLAR D4: calendar names match khal
    val names = dataSource.rows("SELECT name FROM Calendars WHERE deleted = FALSE").map { it["name"] as String }

    assertEquals(setOf("Family-and-Kids", "Kids medical", "Football", "Bills"), names.toSet())
    names.forEach { assert(it in FakeTools.CALENDARS) { "$it is in the test khal config" } }
  }

  @Test
  fun `there are more emails than one list page`() {
    // REQ Viewing emails 1 and 3: 30 shown by default, pages of 50
    assertEquals(68L, count("SELECT COUNT(*) AS n FROM Emails WHERE deleted = FALSE"))
    assertEquals(1L, count("SELECT COUNT(*) AS n FROM Emails WHERE deleted = TRUE"))
  }

  @Test
  fun `every rule has at least one condition and one action`() {
    // REQ Creating a new rule 4 and 8
    for (rule in dataSource.rows("SELECT id, name FROM Rules")) {
      assert(count("SELECT COUNT(*) AS n FROM Conditions WHERE rule_id = ?", rule["id"]) > 0) { "${rule["name"]}" }
      assert(count("SELECT COUNT(*) AS n FROM Rule_actions WHERE rule_id = ?", rule["id"]) > 0) { "${rule["name"]}" }
    }
  }

  @Test
  fun `the related rows all joined up`() {
    assertEquals(13L, count("SELECT COUNT(*) AS n FROM Conditions"))
    assertEquals(17L, count("SELECT COUNT(*) AS n FROM Rule_actions"))
    assertEquals(6L, count("SELECT COUNT(*) AS n FROM Email_rule_matches"))
    assertEquals(11L, count("SELECT COUNT(*) AS n FROM Email_action_results"))
    assertEquals(3L, count("SELECT COUNT(*) AS n FROM Email_errors"))
    assertEquals(3L, count("SELECT COUNT(*) AS n FROM Email_events"))
    assertEquals(3L, count("SELECT COUNT(*) AS n FROM Email_attachments"))
    assertEquals(2L, count("SELECT COUNT(*) AS n FROM Processing_runs"))
    val action = dataSource.row(
        "SELECT ra.calendar_id, c.name FROM Rule_actions ra JOIN Calendars c ON c.id = ra.calendar_id " +
            "WHERE ra.action_type = 'process_bill'"
    )
    assertEquals("Bills", action["name"])
  }

  private fun count(sql: String, vararg params: Any?): Long = (dataSource.row(sql, *params)["n"] as Number).toLong()
}
