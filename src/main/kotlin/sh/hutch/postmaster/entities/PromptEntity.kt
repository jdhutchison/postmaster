package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** A prompt template for the LLM. */
@Entity
@Table(name = "Prompts")
class PromptEntity : AuditedEntity(), SoftDeletable {
  /** Fixed key the code finds a system prompt by; NULL for user-created prompts. */
  @Column(name = "prompt_key", length = 50)
  var promptKey: String? = null

  @Column(name = "name", nullable = false, length = 100)
  var name: String = ""

  /** Markdown template text with `{{placeholder}}` tokens. */
  @Column(name = "contents", nullable = false)
  var contents: String = ""

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null

  val isSystem: Boolean
    get() = promptKey != null
}
