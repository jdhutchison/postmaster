package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.util.Names
import java.time.Instant

/** A khal calendar that events can be added to. */
@Entity
@Table(name = "Calendars")
class CalendarEntity : AuditedEntity(), SoftDeletable {
  /** Name of the calendar in the khal configuration. */
  @Column(name = "name", nullable = false, length = 100)
  var name: String = ""

  /** Lower-cased name while active, NULL once deleted (D20). Maintained automatically. */
  @Column(name = "name_key", length = 100)
  var nameKey: String? = null

  /** What the calendar is for; given to the LLM when it chooses a calendar. */
  @Column(name = "description", nullable = false)
  var description: String = ""

  /** RGB hex colour, `#rrggbb`. */
  @Column(name = "colour", nullable = false, length = 7)
  var colour: String = ""

  @Column(name = "enabled", nullable = false)
  var enabled: Boolean = true

  /** Comma plus space separated durations, e.g. `4h, 1d`, for event notifications. */
  @Column(name = "notification_periods", length = 255)
  var notificationPeriods: String? = null

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null

  @PrePersist
  @PreUpdate
  fun syncNameKey() {
    nameKey = if (deleted) null else Names.key(name)
  }

  override fun markDeleted(now: Instant) {
    super.markDeleted(now)
    nameKey = null
  }
}
