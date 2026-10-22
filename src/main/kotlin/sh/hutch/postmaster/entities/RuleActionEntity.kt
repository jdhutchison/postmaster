package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** An action a rule triggers. */
@Entity
@Table(name = "Rule_actions")
class RuleActionEntity : AuditedEntity(), SoftDeletable {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  var rule: RuleEntity? = null

  /** One of the names in ActionTypes, or a registered custom action type. */
  @Column(name = "action_type", nullable = false, length = 40)
  var actionType: String = ""

  /** Folder for `move`, or email address for `forward_email`. */
  @Column(name = "destination", length = 500)
  var destination: String? = null

  /** Type to classify the email as, for `set_type`. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "type_id")
  var type: TypeEntity? = null

  /** Calendar for `create_events` and `process_bill`. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "calendar_id")
  var calendar: CalendarEntity? = null

  /** Comma plus space separated notification durations for `create_events`. */
  @Column(name = "notifications", length = 255)
  var notifications: String? = null

  @Column(name = "position", nullable = false)
  var position: Int = 0

  @Column(name = "enabled", nullable = false)
  var enabled: Boolean = true

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null
}
