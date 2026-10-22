package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.domain.RuleType
import sh.hutch.postmaster.domain.RuleTypeConverter
import java.time.Instant

/** A rule that matched an email. */
@Entity
@Table(name = "Email_rule_matches")
class EmailRuleMatchEntity : BaseEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  var rule: RuleEntity? = null

  @Convert(converter = RuleTypeConverter::class)
  @Column(name = "rule_type", nullable = false, length = 20)
  var ruleType: RuleType = RuleType.ACTION

  /** Order in which the rules matched. */
  @Column(name = "position", nullable = false)
  var position: Int = 0

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "matched_at", nullable = false)
  var matchedAt: Instant? = null
}
