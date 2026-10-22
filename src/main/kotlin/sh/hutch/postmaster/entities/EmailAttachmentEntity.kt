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

/** An attachment of interest on an email. */
@Entity
@Table(name = "Email_attachments")
class EmailAttachmentEntity : AuditedEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  @Column(name = "filename", nullable = false, length = 500)
  var filename: String = ""

  @Column(name = "content_type", length = 255)
  var contentType: String? = null

  @Column(name = "size_bytes")
  var sizeBytes: Long? = null

  /** Location of the part within the MIME message, used to download it later. */
  @Column(name = "part_path", length = 100)
  var partPath: String? = null

  /** Path relative to the `attachments.path` setting; NULL until downloaded. */
  @Column(name = "stored_path", length = 1000)
  var storedPath: String? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "downloaded_at")
  var downloadedAt: Instant? = null
}
