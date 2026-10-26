package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** A mailbox folder, as last scanned, for choosing where to move email (CLARIFICATIONS.md R9). */
@Entity
@Table(name = "Folders")
class FolderEntity : AuditedEntity() {
  /** The folder's full name, e.g. `Folders/Bills`. */
  @Column(name = "name", nullable = false, length = 255)
  var name: String = ""

  /** When a scan last found the folder. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "scanned_at", nullable = false)
  var scannedAt: Instant? = null
}
