package sh.hutch.postmaster.repositories

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.EmailActionItemEntity
import sh.hutch.postmaster.entities.EmailActionResultEntity
import sh.hutch.postmaster.entities.EmailAttachmentEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.entities.EmailErrorEntity
import sh.hutch.postmaster.entities.EmailEventEntity
import sh.hutch.postmaster.entities.EmailRuleMatchEntity
import sh.hutch.postmaster.entities.ProcessingRunEntity
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.services.MessageIds

@ApplicationScoped
class EmailRepository : PanacheRepository<EmailEntity> {
  /** Looks up by the hash of a normalised Message-ID (see MessageIds), including deleted emails. */
  fun findByMessageId(messageId: String): EmailEntity? =
    find("messageHash", MessageIds.hash(messageId)).firstResult()

  /** Whether an email has been processed before; includes deleted emails so they aren't reprocessed. */
  fun existsByMessageId(messageId: String): Boolean = count("messageHash", MessageIds.hash(messageId)) > 0

  fun findActive(id: Long): EmailEntity? = find("id = ?1 and deleted = false", id).firstResult()

  /** The most recently received active emails. */
  fun listRecent(limit: Int): List<EmailEntity> =
    find("deleted = false order by receivedAt desc, id desc").page(Page.ofSize(limit)).list()
}

@ApplicationScoped
class EmailEventRepository : PanacheRepository<EmailEventEntity> {
  fun listForEmail(emailId: Long): List<EmailEventEntity> = list("email.id = ?1 order by startsAt, id", emailId)
}

@ApplicationScoped
class EmailAttachmentRepository : PanacheRepository<EmailAttachmentEntity> {
  fun listForEmail(emailId: Long): List<EmailAttachmentEntity> = list("email.id = ?1 order by id", emailId)
}

@ApplicationScoped
class EmailRuleMatchRepository : PanacheRepository<EmailRuleMatchEntity> {
  fun listForEmail(emailId: Long): List<EmailRuleMatchEntity> = list("email.id = ?1 order by position, id", emailId)
}

@ApplicationScoped
class EmailActionResultRepository : PanacheRepository<EmailActionResultEntity> {
  fun listForEmail(emailId: Long): List<EmailActionResultEntity> =
    list("email.id = ?1 order by executedAt, id", emailId)
}

@ApplicationScoped
class EmailActionItemRepository : PanacheRepository<EmailActionItemEntity> {
  /** Action items that are in the to-do list and not done yet, of active emails. */
  fun listOpenInTodoList(): List<EmailActionItemEntity> =
    list("completed = false and todoName is not null and email.deleted = false order by id")
}

@ApplicationScoped
class EmailErrorRepository : PanacheRepository<EmailErrorEntity> {
  fun listForEmail(emailId: Long): List<EmailErrorEntity> = list("email.id = ?1 order by createdAt, id", emailId)

  fun countForEmail(emailId: Long): Long = count("email.id", emailId)
}

@ApplicationScoped
class ProcessingRunRepository : PanacheRepository<ProcessingRunEntity> {
  fun findLatest(): ProcessingRunEntity? = find("order by startedAt desc, id desc").firstResult()

  fun findRunning(): ProcessingRunEntity? =
    find("status = ?1 order by startedAt desc, id desc", RunStatus.RUNNING).firstResult()
}
