package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.domain.RunStatus
import sh.hutch.postmaster.domain.RunStatusConverter
import sh.hutch.postmaster.domain.RunTrigger
import sh.hutch.postmaster.domain.RunTriggerConverter
import java.time.Instant

/** A run of the email processing pipeline. */
@Entity
@Table(name = "Processing_runs")
class ProcessingRunEntity : BaseEntity() {
  @Convert(converter = RunTriggerConverter::class)
  @Column(name = "trigger_source", nullable = false, length = 20)
  var triggerSource: RunTrigger = RunTrigger.SCHEDULE

  @Convert(converter = RunStatusConverter::class)
  @Column(name = "status", nullable = false, length = 20)
  var status: RunStatus = RunStatus.RUNNING

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "started_at", nullable = false)
  var startedAt: Instant? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "finished_at")
  var finishedAt: Instant? = null

  @Column(name = "emails_found", nullable = false)
  var emailsFound: Int = 0

  @Column(name = "emails_processed", nullable = false)
  var emailsProcessed: Int = 0

  @Column(name = "emails_failed", nullable = false)
  var emailsFailed: Int = 0

  @Column(name = "error")
  var error: String? = null
}
