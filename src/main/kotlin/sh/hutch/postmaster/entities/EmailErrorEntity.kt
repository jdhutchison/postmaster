package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.domain.ErrorPhase
import sh.hutch.postmaster.domain.ErrorPhaseConverter
import sh.hutch.postmaster.util.Times
import java.time.Instant

/** An error that happened while processing an email. */
@Entity
@Table(name = "Email_errors")
class EmailErrorEntity : BaseEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  @Convert(converter = ErrorPhaseConverter::class)
  @Column(name = "phase", nullable = false, length = 20)
  var phase: ErrorPhase = ErrorPhase.ACTION

  @Column(name = "message", nullable = false)
  var message: String = ""

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  var createdAt: Instant? = null

  @PrePersist
  fun onCreate() {
    if (createdAt == null) {
      createdAt = Times.now()
    }
  }
}
