package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.util.Times
import java.time.Instant

// Instants are mapped with @JdbcTypeCode(SqlTypes.TIMESTAMP) so Hibernate binds them with the UTC
// JDBC time zone (quarkus.hibernate-orm.jdbc.timezone). The column then holds UTC wall time on both
// MariaDB and H2, whatever the JVM's default zone. Hibernate's default for Instant differs between
// the two dialects.

/** An entity with a generated id. */
@MappedSuperclass
abstract class BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
}

/** An entity with `created_at` and `last_updated_at` columns, maintained automatically. */
@MappedSuperclass
abstract class AuditedEntity : BaseEntity() {
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  var createdAt: Instant? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "last_updated_at", nullable = false)
  var lastUpdatedAt: Instant? = null

  @PrePersist
  fun onCreate() {
    val now = Times.now()
    if (createdAt == null) {
      createdAt = now
    }
    lastUpdatedAt = now
  }

  @PreUpdate
  fun onUpdate() {
    lastUpdatedAt = Times.now()
  }
}

/** An entity that is soft deleted: flagged and timestamped rather than removed. */
interface SoftDeletable {
  var deleted: Boolean
  var deletedAt: Instant?

  fun markDeleted(now: Instant = Times.now()) {
    deleted = true
    deletedAt = now
  }
}
