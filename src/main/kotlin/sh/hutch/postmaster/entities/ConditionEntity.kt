package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import sh.hutch.postmaster.domain.ConditionAttribute
import sh.hutch.postmaster.domain.ConditionAttributeConverter
import sh.hutch.postmaster.domain.MatchType
import sh.hutch.postmaster.domain.MatchTypeConverter

/** A test a rule applies to an email. Removed for real when taken off a rule. */
@Entity
@Table(name = "Conditions")
class ConditionEntity : AuditedEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  var rule: RuleEntity? = null

  @Convert(converter = ConditionAttributeConverter::class)
  @Column(name = "attribute", nullable = false, length = 20)
  var attribute: ConditionAttribute = ConditionAttribute.SUBJECT

  @Convert(converter = MatchTypeConverter::class)
  @Column(name = "match_type", nullable = false, length = 20)
  var matchType: MatchType = MatchType.IS

  /** What to match; NULL for match types that take no test value. */
  @Column(name = "test", length = 1000)
  var test: String? = null

  @Column(name = "position", nullable = false)
  var position: Int = 0
}
