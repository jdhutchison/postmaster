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
import java.time.LocalDate

/**
 * Something an email asks the user to do (CLARIFICATIONS.md R5). Change [completed] through
 * [EmailEntity.completeActionItem] so the email's action required flag follows.
 */
@Entity
@Table(name = "Email_action_items")
class EmailActionItemEntity : AuditedEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  @Column(name = "position", nullable = false)
  var position: Int = 0

  @Column(name = "description", nullable = false, length = 500)
  var description: String = ""

  @JdbcTypeCode(SqlTypes.LOCAL_DATE)
  @Column(name = "due_date")
  var dueDate: LocalDate? = null

  /** Details from the email needed to do it. */
  @Column(name = "notes")
  var notes: String? = null

  @Column(name = "completed", nullable = false)
  var completed: Boolean = false
    internal set

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "completed_at")
  var completedAt: Instant? = null
    internal set

  /** The item's file name in the to-do list, without `.md`, once update_todo_list has written it. */
  @Column(name = "todo_name", length = 255)
  var todoName: String? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "todo_created_at")
  var todoCreatedAt: Instant? = null
}
