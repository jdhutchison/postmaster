package sh.hutch.postmaster.db

import io.agroal.api.AgroalDataSource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import sh.hutch.postmaster.testsupport.applicationTables
import sh.hutch.postmaster.testsupport.columns
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.rows
import sh.hutch.postmaster.testsupport.update
import java.sql.SQLException

/**
 * The schema created by the Flyway migrations (PLAN 1.1). The test database
 * keeps identifiers in the case they were written, like MariaDB on Linux.
 *
 * Column names follow REQUIREMENTS.md "Database schema/entities", adapted as
 * the requirements allow and as recorded in the schema migration:
 * Settings key/value -> setting_key/setting_value, Emails type -> type_id,
 * received -> received_at, Email_events calendar -> calendar_id and
 * timestamp -> starts_at, Rule_actions type -> type_id. The lists of
 * attachments, matched rules and errors on Emails are normalised into their
 * own tables (CLAR D7).
 */
@QuarkusTest
class SchemaTest {
  @Inject
  lateinit var dataSource: AgroalDataSource

  @Test
  fun `every planned table exists`() {
    // PLAN 1.1 (tables from REQ "Database schema/entities" and CLAR D7)
    val tables = dataSource.applicationTables()

    for (table in REQUIRED_COLUMNS.keys) {
      assertTrue(table in tables, "table $table exists; found $tables")
    }
  }

  @Test
  fun `table names match exactly, case included`() {
    // REQ Coding style; CLAR D20 (table names match exactly: MariaDB on Linux
    // compares table names case-sensitively). Queried from INFORMATION_SCHEMA
    // with a case-sensitive comparison.
    for (table in REQUIRED_COLUMNS.keys) {
      val matches = dataSource.rows(
          "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME = ?",
          table
      )
      assertEquals(listOf(table), matches.map { it["table_name"] }, "exactly one table named $table")
    }
    val variants = dataSource.applicationTables().filter { name ->
      REQUIRED_COLUMNS.keys.any { it.equals(name, ignoreCase = true) && it != name }
    }
    assertTrue(variants.isEmpty(), "no tables differ from the planned names only by case: $variants")
  }

  @Test
  fun `no table or column name is a reserved word in MariaDB or H2`() {
    // CLAR D20: reserved-word columns are renamed rather than quoted
    for (table in dataSource.applicationTables()) {
      assertFalse(ReservedWords.isReserved(table), "table $table is a reserved word")
      for (column in dataSource.columns(table)) {
        assertFalse(ReservedWords.isReserved(column), "column $table.$column is a reserved word")
      }
    }
  }

  @Test
  fun `unique columns fit MariaDB's index size limit`() {
    // CLAR D20: InnoDB index keys are at most 3072 bytes, and utf8mb4 needs up
    // to 4 bytes a character, so a unique VARCHAR can have at most 768 characters
    val unique = Regex("UNIQUE\\s*\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
    val createTable = Regex("CREATE TABLE\\s+(\\w+)\\s*\\(", RegexOption.IGNORE_CASE)
    val varchar = Regex("^VARCHAR\\((\\d+)\\)", RegexOption.IGNORE_CASE)
    var checked = 0
    for (script in MigrationScripts.all().values) {
      for (table in createTable.findAll(script).map { it.groupValues[1] }) {
        val body = MigrationScripts.createTable(table)
        for (constraint in unique.findAll(body)) {
          val bytes = constraint.groupValues[1].split(",").map { it.trim() }.sumOf { column ->
            val declaration = MigrationScripts.columnDeclaration(body, column)
            when {
              declaration.uppercase().startsWith("BIGINT") || declaration.uppercase().startsWith("INT") -> 8
              else -> {
                val length = varchar.find(declaration)?.groupValues?.get(1)?.toInt()
                    ?: error("$table.$column in a unique key must be a VARCHAR or integer, was: $declaration")
                length * 4
              }
            }
          }
          assertTrue(bytes <= 3072, "unique key ${constraint.value} on $table needs up to $bytes bytes")
          checked++
        }
      }
    }
    val uniqueIndex = Regex("CREATE\\s+UNIQUE\\s+INDEX\\s+\\w+\\s+ON\\s+(\\w+)\\s*\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
    for (script in MigrationScripts.all().values) {
      for (index in uniqueIndex.findAll(script)) {
        val table = index.groupValues[1]
        val body = MigrationScripts.createTable(table)
        val bytes = index.groupValues[2].split(",").map { it.trim() }.sumOf { column ->
          val declaration = MigrationScripts.columnDeclaration(body, column)
          varchar.find(declaration)?.groupValues?.get(1)?.toInt()?.times(4) ?: 8
        }
        assertTrue(bytes <= 3072, "unique index ${index.value} needs up to $bytes bytes")
        checked++
      }
    }
    assertTrue(checked > 0, "found the unique keys in the migrations")
  }

  @Test
  fun `table names are snake case with the first letter capitalised`() {
    // REQ Coding style: "named after the thing they represent, in Snake Case
    // with the first letter capitalised, eg. Rules or Emails"
    for (table in dataSource.applicationTables()) {
      assertTrue(TABLE_NAME.matches(table), "table name '$table' is Snake_case with a leading capital")
    }
  }

  @Test
  fun `column names are lower snake case`() {
    // REQ Coding style: "Columns are to in snake case but all lower case, e.g. received_at"
    for (table in dataSource.applicationTables()) {
      for (column in dataSource.columns(table)) {
        assertTrue(COLUMN_NAME.matches(column), "column '$table.$column' is lower snake case")
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tablesWithRequiredColumns")
  fun `tables have the columns the requirements and clarifications need`(table: String) {
    val columns = dataSource.columns(table)

    val missing = REQUIRED_COLUMNS.getValue(table) - columns
    assertTrue(missing.isEmpty(), "$table is missing $missing; has $columns")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("softDeletedTables")
  fun `soft-deletable tables record whether and when a row was deleted`(table: String) {
    // REQ Types 5, Rules/Rule_Actions schema, Editing a rule 5; CLAR D3, D11
    val columns = dataSource.columns(table)

    assertTrue("deleted" in columns && "deleted_at" in columns, "$table has deleted and deleted_at; has $columns")
  }

  @Test
  @TestTransaction
  fun `a rule's priority defaults to zero`() {
    // REQ Creating a new rule 3: "The default is zero."
    dataSource.update(
        "INSERT INTO Rules (name, description, rule_type, created_at, last_updated_at) " +
            "VALUES ('Default priority', 'Checks the default', 'classification', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    )

    val rule = dataSource.row("SELECT priority FROM Rules WHERE name = 'Default priority'")
    assertEquals(0, (rule["priority"] as Number).toInt())
  }

  @Test
  @TestTransaction
  fun `a calendar is enabled by default`() {
    // REQ Calendars schema: "enabled - boolean ... true by default"
    dataSource.update(
        "INSERT INTO Calendars (name, description, colour, created_at, last_updated_at) " +
            "VALUES ('Default enabled', 'Checks the default', '#123456', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    )

    val calendar = dataSource.row("SELECT enabled FROM Calendars WHERE name = 'Default enabled'")
    assertEquals(true, calendar["enabled"])
  }

  @Test
  @TestTransaction
  fun `an email's message id is unique`() {
    // CLAR D7 (message_id unique) and Q14 (processed email is recognised by its Message-ID)
    val insert = "INSERT INTO Emails (message_id, status, received_at, created_at, last_updated_at) " +
        "VALUES ('<duplicate@test>', 'processed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    dataSource.update(insert)

    assertThrows<SQLException> { dataSource.update(insert) }
  }

  @Test
  @TestTransaction
  fun `long email text is stored in full`() {
    // REQ Emails schema: message and summary are long text; CLAR D7 adds the
    // HTML body and the linked page's contents and summary
    val long = "x".repeat(200_000)
    dataSource.update(
        "INSERT INTO Emails (message_id, status, message, html_message, summary, link_contents, link_summary, " +
            "received_at, created_at, last_updated_at) " +
            "VALUES ('<long@test>', 'processed', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        long, long, long, long, long
    )

    val email = dataSource.row(
        "SELECT message, html_message, summary, link_contents, link_summary FROM Emails WHERE message_id = '<long@test>'"
    )
    for ((column, value) in email) {
      assertEquals(long.length, value.toString().length, "$column keeps all ${long.length} characters")
    }
  }

  @Test
  fun `long email text columns are LONGTEXT in the MariaDB migrations`() {
    // REQ Emails schema "message (long text)", "summary (long text)". MariaDB's
    // TEXT holds only 64 KB, which H2 can't show, so this checks the DDL that
    // production runs.
    val emails = MigrationScripts.createTable("Emails")

    for (column in listOf("message", "html_message", "summary", "link_contents", "link_summary")) {
      val declaration = MigrationScripts.columnDeclaration(emails, column)
      assertTrue(
          declaration.uppercase().startsWith("LONGTEXT"),
          "Emails.$column is LONGTEXT, was: $declaration"
      )
    }
  }

  @Test
  @TestTransaction
  fun `a bill amount keeps its cents`() {
    // REQ Emails schema: bill_amount, the amount needed to pay
    dataSource.update(
        "INSERT INTO Emails (message_id, status, bill_amount, received_at, created_at, last_updated_at) " +
            "VALUES ('<amount@test>', 'processed', 1234.56, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    )

    val email = dataSource.row("SELECT bill_amount FROM Emails WHERE message_id = '<amount@test>'")
    assertEquals(0, (email["bill_amount"] as Number).toDouble().compareTo(1234.56))
  }

  companion object {
    private val TABLE_NAME = Regex("^[A-Z][a-z0-9]*(_[a-z0-9]+)*$")
    private val COLUMN_NAME = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    /** Columns each table must have, from REQ "Database schema/entities" (adapted, see class doc) and the CLAR items noted */
    val REQUIRED_COLUMNS: Map<String, Set<String>> = mapOf(
        // REQ Settings; CLAR D9 adds a type and a description
        "Settings" to setOf("id", "setting_key", "label", "setting_value", "value_type", "description"),
        // REQ Types; CLAR Q16 system key; CLAR D3 soft delete
        "Types" to setOf("id", "name", "description", "colour", "system_key", "deleted", "deleted_at"),
        // REQ Calendars; CLAR D3 soft delete
        "Calendars" to setOf(
            "id", "name", "description", "colour", "enabled", "notification_periods", "deleted", "deleted_at"
        ),
        // REQ Prompts; CLAR Q15 fixed key; CLAR D3 soft delete
        "Prompts" to setOf("id", "name", "contents", "prompt_key", "deleted", "deleted_at"),
        // REQ Rules; CLAR D5 description; REQ Creating a new rule 2 (type)
        "Rules" to setOf(
            "id", "name", "description", "rule_type", "priority", "enabled", "deleted",
            "created_at", "last_updated_at", "deleted_at"
        ),
        // REQ Conditions
        "Conditions" to setOf("id", "rule_id", "attribute", "match_type", "test"),
        // REQ Rule_Actions; CLAR D6 notifications and position; CLAR D6/Q16 calendar destination
        "Rule_actions" to setOf(
            "id", "rule_id", "action_type", "destination", "type_id", "calendar_id", "notifications", "position",
            "enabled", "deleted", "created_at", "last_updated_at", "deleted_at"
        ),
        // REQ Emails; CLAR D7 and Q13 revision
        "Emails" to setOf(
            "id", "subject", "sender", "type_id", "message", "summary", "link",
            "bill_sender", "bill_due_date", "bill_amount", "bill_pay_link", "bill_download_url",
            "response_required", "moved_to", "received_at", "processed_at",
            "message_id", "imap_uid", "uid_validity", "folder", "html_message", "link_contents", "link_summary",
            "status", "action_required", "action_description", "is_read", "deleted", "deleted_at"
        ),
        // REQ Email_events; CLAR D8
        "Email_events" to setOf(
            "id", "email_id", "calendar_id", "title", "starts_at", "location", "is_added",
            "created_at", "last_updated_at", "notes", "ends_at", "all_day", "khal_uid"
        ),
        // CLAR D7: per-attachment downloads (REQ Viewing an individual email 10, 11)
        "Email_attachments" to setOf("id", "email_id", "filename"),
        // CLAR D7: the rules that matched (REQ Emails classification_rules, action_rules)
        "Email_rule_matches" to setOf("id", "email_id", "rule_id"),
        // CLAR D7: the actions each matching rule triggered (REQ Viewing an individual email 5, 6)
        "Email_action_results" to setOf("id", "email_id", "rule_id", "action_type"),
        // CLAR D7: processing errors (REQ Emails errors)
        "Email_errors" to setOf("id", "email_id", "message"),
        // PLAN 1.1, 5.6: each processing run is recorded
        "Processing_runs" to setOf("id", "status")
    )

    @JvmStatic
    fun tablesWithRequiredColumns(): List<String> = REQUIRED_COLUMNS.keys.toList()

    @JvmStatic
    fun softDeletedTables(): List<String> = listOf("Types", "Calendars", "Prompts", "Rules", "Rule_actions", "Emails")
  }
}
