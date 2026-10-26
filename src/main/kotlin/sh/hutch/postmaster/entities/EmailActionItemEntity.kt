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
import sh.hutch.postmaster.models.ActionItemKind
import sh.hutch.postmaster.models.TodoStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Something an email asks the user to do (CLARIFICATIONS.md R5). Change [completed] and [inProgress]
 * through [EmailEntity.completeActionItem] or [EmailEntity.setActionItemStatus] so the email's
 * action required flag follows.
 */
@Entity
@Table(name = "Email_action_items")
class EmailActionItemEntity : AuditedEntity() {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "email_id", nullable = false)
  var email: EmailEntity? = null

  @Column(name = "position", nullable = false)
  var position: Int = 0

  /** What the item is for: something the email asks for, a reply, or a task added by hand (R11). */
  @Convert(converter = ActionItemKindConverter::class)
  @Column(name = "kind", nullable = false, length = 10)
  var kind: ActionItemKind = ActionItemKind.ACTION

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

  /** Marked in progress in the to-do list (CLARIFICATIONS.md R9); it still needs doing. */
  @Column(name = "in_progress", nullable = false)
  var inProgress: Boolean = false
    internal set

  /** The item's file name in the to-do list, without `.md`, once update_todo_list has written it. */
  @Column(name = "todo_name", length = 255)
  var todoName: String? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "todo_created_at")
  var todoCreatedAt: Instant? = null

  /** Open, in progress or done. */
  val status: TodoStatus
    get() = when {
      completed -> TodoStatus.DONE
      inProgress -> TodoStatus.IN_PROGRESS
      else -> TodoStatus.OPEN
    }
}
