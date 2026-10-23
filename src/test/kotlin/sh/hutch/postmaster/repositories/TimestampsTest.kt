package sh.hutch.postmaster.repositories

import io.agroal.api.AgroalDataSource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.services.MessageIds
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone

/**
 * Timestamps survive the trip to the database and back (coordinator request;
 * CLAR D20 "timestamps are UTC DATETIMEs"; docs/dev/contracts.md). Instants
 * are stored as UTC wall-clock times whatever the JVM's time zone; event
 * times are local wall-clock times and are stored unshifted. The JVM zone is
 * set to Melbourne, where production runs, for each test.
 */
@QuarkusTest
class TimestampsTest {
  @Inject
  lateinit var runs: ProcessingRunRepository

  @Inject
  lateinit var emails: EmailRepository

  @Inject
  lateinit var events: EmailEventRepository

  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var entityManager: EntityManager

  private lateinit var originalZone: TimeZone

  @BeforeEach
  fun useMelbourneTime() {
    originalZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("Australia/Melbourne"))
  }

  @AfterEach
  fun restoreTimeZone() {
    TimeZone.setDefault(originalZone)
  }

  @Test
  @TestTransaction
  fun `an instant is stored as its UTC time and read back unchanged`() {
    val started = Instant.parse("2026-10-22T00:30:00Z")
    val run = ProcessingRunEntity().apply {
      triggerSource = RunTrigger.MANUAL
      status = RunStatus.SUCCEEDED
      startedAt = started
    }
    runs.persist(run)
    entityManager.flush()

    assertEquals(LocalDateTime.parse("2026-10-22T00:30:00"), rawTime("Processing_runs", "started_at", run.id!!))
    entityManager.clear()
    assertEquals(started, runs.findById(run.id!!)!!.startedAt)
  }

  @Test
  @TestTransaction
  fun `control - plain JDBC in this JVM writes Melbourne time, so the UTC result above is not an accident`() {
    // Without the UTC handling, the same instant is stored as 11:30 am
    // Melbourne time. If this fails, the zone change isn't taking effect and
    // the other tests here prove nothing.
    val started = Instant.parse("2026-10-22T00:30:00Z")
    val id = dataSource.connection.use { connection ->
      connection.prepareStatement(
          "INSERT INTO Processing_runs (trigger_source, status, started_at) VALUES ('manual', 'running', ?)",
          Statement.RETURN_GENERATED_KEYS
      ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(started))
        statement.executeUpdate()
        statement.generatedKeys.use { keys ->
          keys.next()
          keys.getLong(1)
        }
      }
    }

    assertEquals(LocalDateTime.parse("2026-10-22T11:30:00"), rawTime("Processing_runs", "started_at", id))
  }

  @Test
  @TestTransaction
  fun `an email's received time is stored in UTC`() {
    // In Melbourne (UTC+11 in October) this is 9:15 pm local time
    val received = Instant.parse("2026-10-22T10:15:00Z")
    val email = newEmail("utc-check@example.test", received)
    emails.persist(email)
    entityManager.flush()

    assertEquals(LocalDateTime.parse("2026-10-22T10:15:00"), rawTime("Emails", "received_at", email.id!!))
    entityManager.clear()
    assertEquals(received, emails.findActive(email.id!!)!!.receivedAt)
  }

  @Test
  @TestTransaction
  fun `an event time is a local wall-clock time stored unshifted`() {
    // docs/dev/contracts.md: Email_events times are local times in processing.timezone
    val email = newEmail("event-time@example.test", Instant.parse("2026-10-22T10:15:00Z"))
    emails.persist(email)
    val starts = LocalDateTime.parse("2026-11-05T09:00:00")
    val event = EmailEventEntity().apply {
      this.email = email
      title = "Zoo excursion"
      startsAt = starts
      endsAt = starts.plusHours(6)
    }
    events.persist(event)
    entityManager.flush()

    assertEquals(starts, rawTime("Email_events", "starts_at", event.id!!))
    assertEquals(starts.plusHours(6), rawTime("Email_events", "ends_at", event.id!!))
    entityManager.clear()
    assertEquals(starts, events.findById(event.id!!)!!.startsAt)
  }

  private fun newEmail(messageId: String, received: Instant) = EmailEntity().apply {
    this.messageId = messageId
    messageHash = MessageIds.hash(messageId)
    status = EmailStatus.PROCESSED
    receivedAt = received
  }

  /** The column's stored wall-clock value, read without any time zone conversion */
  private fun rawTime(table: String, column: String, id: Long): LocalDateTime =
      dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT $column FROM $table WHERE id = ?").use { statement ->
          statement.setLong(1, id)
          statement.executeQuery().use { rows ->
            check(rows.next()) { "no $table row $id" }
            rows.getObject(1, LocalDateTime::class.java)
          }
        }
      }
}
