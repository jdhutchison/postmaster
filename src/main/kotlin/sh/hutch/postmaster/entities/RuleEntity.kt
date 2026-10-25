package sh.hutch.postmaster.entities

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.models.ConditionJoin
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.util.Names
import java.time.Instant

/**
 * A classification or action rule. It matches an email when all its conditions match, or any of
 * them with [conditionJoin] `any` (Q21, R8).
 */
@Entity
@Table(name = "Rules")
class RuleEntity : AuditedEntity(), SoftDeletable {
  @Column(name = "name", nullable = false, length = 100)
  var name: String = ""

  /** Lower-cased name while active, NULL once deleted (D20). Maintained automatically. */
  @Column(name = "name_key", length = 100)
  var nameKey: String? = null

  @Column(name = "description", nullable = false)
  var description: String = ""

  @Convert(converter = RuleTypeConverter::class)
  @Column(name = "rule_type", nullable = false, length = 20)
  var ruleType: RuleType = RuleType.ACTION

  /** Higher priorities are applied first. */
  @Column(name = "priority", nullable = false)
  var priority: Int = 0

  @Column(name = "enabled", nullable = false)
  var enabled: Boolean = true

  /** Whether all the conditions must match (AND) or any one of them (OR). */
  @Convert(converter = ConditionJoinConverter::class)
  @Column(name = "condition_join", nullable = false, length = 3)
  var conditionJoin: ConditionJoin = ConditionJoin.ALL

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null

  @OneToMany(mappedBy = "rule", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("position ASC, id ASC")
  var conditions: MutableList<ConditionEntity> = mutableListOf()

  /** All actions, including soft-deleted ones. */
  @OneToMany(mappedBy = "rule", cascade = [CascadeType.ALL])
  @OrderBy("position ASC, id ASC")
  var actions: MutableList<RuleActionEntity> = mutableListOf()

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
