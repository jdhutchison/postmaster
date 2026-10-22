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

/** A type that email can be classified as. */
@Entity
@Table(name = "Types")
class TypeEntity : AuditedEntity(), SoftDeletable {
  /** Fixed key the code finds a system type by (`bill`); NULL for user-created types. */
  @Column(name = "system_key", length = 50)
  var systemKey: String? = null

  @Column(name = "name", nullable = false, length = 100)
  var name: String = ""

  /** Lower-cased name while active, NULL once deleted (D20). Maintained automatically. */
  @Column(name = "name_key", length = 100)
  var nameKey: String? = null

  @Column(name = "description")
  var description: String? = null

  /** RGB hex colour, `#rrggbb`. */
  @Column(name = "colour", nullable = false, length = 7)
  var colour: String = ""

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null

  val isSystem: Boolean
    get() = systemKey != null

  val isBill: Boolean
    get() = systemKey == BILL_KEY

  @PrePersist
  @PreUpdate
  fun syncNameKey() {
    nameKey = if (deleted) null else Names.key(name)
  }

  override fun markDeleted(now: Instant) {
    super.markDeleted(now)
    nameKey = null
  }

  companion object {
    /** System key of the Bill type. */
    const val BILL_KEY = "bill"
  }
}
