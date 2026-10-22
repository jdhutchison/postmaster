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
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import java.time.Instant

/** An action taken on an email, by a rule or from the UI or MCP. */
@Entity
@Table(name = "Email_action_results")
class EmailActionResultEntity : BaseEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  /** Rule that triggered the action; NULL for actions from the UI or MCP. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "rule_id")
  var rule: RuleEntity? = null

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "rule_action_id")
  var ruleAction: RuleActionEntity? = null

  @Column(name = "action_type", nullable = false, length = 40)
  var actionType: String = ""

  @Convert(converter = ActionSourceConverter::class)
  @Column(name = "source", nullable = false, length = 20)
  var source: ActionSource = ActionSource.RULE

  @Convert(converter = ActionResultStatusConverter::class)
  @Column(name = "status", nullable = false, length = 20)
  var status: ActionResultStatus = ActionResultStatus.DONE

  @Column(name = "detail")
  var detail: String? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "executed_at", nullable = false)
  var executedAt: Instant? = null
}
