package sh.hutch.postmaster.entities

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ProcessingProgress
import sh.hutch.postmaster.services.MessageIds
import sh.hutch.postmaster.util.Times
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** An email the system has processed. */
@Entity
@Table(name = "Emails")
class EmailEntity : AuditedEntity(), SoftDeletable {
  /** Normalised Message-ID (see MessageIds). */
  @Column(name = "message_id", nullable = false, length = 998)
  var messageId: String = ""

  /** SHA-256 of [messageId]; unique. Maintained automatically. */
  @Column(name = "message_hash", nullable = false, length = 64)
  var messageHash: String = ""

  @Column(name = "imap_uid")
  var imapUid: Long? = null

  @Column(name = "uid_validity")
  var uidValidity: Long? = null

  /** IMAP folder the email is currently in. */
  @Column(name = "folder", length = 255)
  var folder: String? = null

  @Column(name = "subject")
  var subject: String? = null

  /** Sender's address. */
  @Column(name = "sender", length = 320)
  var sender: String? = null

  @Column(name = "sender_name", length = 255)
  var senderName: String? = null

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "type_id")
  var type: TypeEntity? = null
    protected set

  @Convert(converter = EmailStatusConverter::class)
  @Column(name = "status", nullable = false, length = 20)
  var status: EmailStatus = EmailStatus.PENDING

  /** The last processing step saved, while the email is pending; null once processing has finished (R6). */
  @Convert(converter = ProcessingProgressConverter::class)
  @Column(name = "progress", length = 20)
  var progress: ProcessingProgress? = null

  /** How many times processing has started on the email since it was recorded or reprocessed. */
  @Column(name = "attempts", nullable = false)
  var attempts: Int = 0

  /** Plain text body. */
  @Column(name = "message")
  var message: String? = null

  /** HTML body. */
  @Column(name = "html_message")
  var htmlMessage: String? = null

  @Column(name = "summary")
  var summary: String? = null

  @Column(name = "link", length = 2000)
  var link: String? = null

  @Column(name = "link_contents")
  var linkContents: String? = null

  @Column(name = "link_summary")
  var linkSummary: String? = null

  @Column(name = "bill_sender", length = 255)
  var billSender: String? = null

  @JdbcTypeCode(SqlTypes.LOCAL_DATE)
  @Column(name = "bill_due_date")
  var billDueDate: LocalDate? = null

  @Column(name = "bill_amount", precision = 12, scale = 2)
  var billAmount: BigDecimal? = null

  @Column(name = "bill_pay_link", length = 2000)
  var billPayLink: String? = null

  @Column(name = "bill_download_url", length = 2000)
  var billDownloadUrl: String? = null

  @Column(name = "response_required", nullable = false)
  var responseRequired: Boolean = false

  /**
   * True when the email has action items that aren't done; never true for bills (Q13). Kept in step
   * with [actionItems].
   */
  @Column(name = "action_required", nullable = false)
  var actionRequired: Boolean = false
    protected set

  /** The open action items' descriptions, separated by "; ". Kept in step with [actionItems]. */
  @Column(name = "action_description")
  var actionDescription: String? = null
    protected set

  /** Folder the email was moved to by an action. */
  @Column(name = "moved_to", length = 255)
  var movedTo: String? = null

  @Column(name = "is_read", nullable = false)
  var isRead: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "received_at", nullable = false)
  var receivedAt: Instant? = null

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "processed_at")
  var processedAt: Instant? = null

  @Column(name = "deleted", nullable = false)
  override var deleted: Boolean = false

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deleted_at")
  override var deletedAt: Instant? = null

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("startsAt ASC, id ASC")
  var events: MutableList<EmailEventEntity> = mutableListOf()

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("id ASC")
  var attachments: MutableList<EmailAttachmentEntity> = mutableListOf()

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("position ASC, id ASC")
  var ruleMatches: MutableList<EmailRuleMatchEntity> = mutableListOf()

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("executedAt ASC, id ASC")
  var actionResults: MutableList<EmailActionResultEntity> = mutableListOf()

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("position ASC, id ASC")
  var actionItems: MutableList<EmailActionItemEntity> = mutableListOf()

  @OneToMany(mappedBy = "email", cascade = [CascadeType.ALL], orphanRemoval = true)
  @OrderBy("createdAt ASC, id ASC")
  var errors: MutableList<EmailErrorEntity> = mutableListOf()

  /** Sets the type. Setting it to Bill drops the action items (Q13). */
  fun assignType(newType: TypeEntity?) {
    type = newType
    if (newType?.isBill == true) {
      dropActionItems()
    } else {
      syncActionRequired()
    }
  }

  /** The action items that aren't done yet. */
  fun openActionItems(): List<EmailActionItemEntity> = actionItems.filter { !it.completed }

  /**
   * Adds something the email asks the user to do (CLARIFICATIONS.md R5) and returns it. Nothing is
   * added for bills (Q13), or when an item with the same description is already there, as it is
   * when an email is reprocessed.
   */
  fun addActionItem(description: String, dueDate: LocalDate? = null, notes: String? = null): EmailActionItemEntity? {
    val text = description.trim().take(MAX_DESCRIPTION)
    if (type?.isBill == true || text.isEmpty() || actionItems.any { it.description.equals(text, ignoreCase = true) }) {
      return null
    }
    val item = EmailActionItemEntity().also {
      it.email = this
      it.position = (actionItems.maxOfOrNull { existing -> existing.position } ?: -1) + 1
      it.description = text
      it.dueDate = dueDate
      it.notes = notes?.trim()?.ifEmpty { null }
    }
    actionItems.add(item)
    syncActionRequired()
    return item
  }

  /** Marks [item] done. */
  fun completeActionItem(item: EmailActionItemEntity) {
    if (!item.completed) {
      item.completed = true
      item.completedAt = Times.now()
    }
    syncActionRequired()
  }

  /**
   * Removes the action items that aren't in the to-do list: before the email is processed again, and
   * when it becomes a bill. Items in the to-do list stay, so they're never written there twice.
   */
  fun dropActionItems() {
    actionItems.removeIf { it.todoName == null }
    syncActionRequired()
  }

  private fun syncActionRequired() {
    val open = if (type?.isBill == true) emptyList() else openActionItems()
    actionRequired = open.isNotEmpty()
    actionDescription = open.joinToString("; ") { it.description }.ifEmpty { null }
  }

  @PrePersist
  @PreUpdate
  fun syncMessageHash() {
    messageHash = MessageIds.hash(messageId)
  }

  companion object {
    const val MAX_DESCRIPTION = 500
  }
}
