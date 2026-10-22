package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * An event found in an email. [startsAt] and [endsAt] are local wall-clock times in the
 * `processing.timezone` setting, bound without any time zone conversion.
 */
@Entity
@Table(name = "Email_events")
class EmailEventEntity : AuditedEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  /** Calendar the event was added to, or the suggested calendar if it hasn't been added. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "calendar_id")
  var calendar: CalendarEntity? = null

  @Column(name = "title", nullable = false, length = 500)
  var title: String = ""

  @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
  @Column(name = "starts_at", nullable = false)
  var startsAt: LocalDateTime? = null

  @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
  @Column(name = "ends_at")
  var endsAt: LocalDateTime? = null

  @Column(name = "all_day", nullable = false)
  var allDay: Boolean = false

  @Column(name = "location", length = 500)
  var location: String? = null

  @Column(name = "notes")
  var notes: String? = null

  /** Whether the event has been saved to its calendar. */
  @Column(name = "is_added", nullable = false)
  var isAdded: Boolean = false

  /** UID of the event in khal, once added. */
  @Column(name = "khal_uid", length = 255)
  var khalUid: String? = null
}
