package sh.hutch.postmaster.services

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.entities.EmailActionItemEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.repositories.EmailActionItemRepository
import sh.hutch.postmaster.util.Mdc
import sh.hutch.postmaster.util.Times
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps action items and the markdown to-do list in the `todo.path` folder in step
 * (CLARIFICATIONS.md R5): writes items to it, ticks them off when they're done here, and marks
 * them done here when they've been ticked off there. One change to the files at a time.
 */
@ApplicationScoped
class TodoListService(private val settings: SettingsService, private val items: EmailActionItemRepository) {
  private val lock = ReentrantLock()

  /**
   * Writes [item] of [email] to the to-do list and records the file's name against it. Call within a
   * transaction.
   *
   * @throws TodoListException if the list can't be written
   */
  fun add(email: EmailEntity, item: EmailActionItemEntity): String {
    val now = Times.now()
    val created = LocalDateTime.ofInstant(now, settings.getZoneId(SettingKeys.PROCESSING_TIMEZONE)).truncatedTo(ChronoUnit.SECONDS)
    val entry = TodoEntry(item.description, created, item.dueDate, body(email, item))
    val name = lock.withLock { list().add(entry) }
    item.todoName = name
    item.todoCreatedAt = now
    LOG.infof("Added '%s' to the to-do list", name)
    return name
  }

  /**
   * Ticks [item] off in the to-do list, if it's there. A file or task that has gone is left alone.
   *
   * @throws TodoListException if the list can't be written
   */
  fun complete(item: EmailActionItemEntity) {
    val name = item.todoName ?: return
    lock.withLock { list().complete(name) }
    LOG.infof("Ticked '%s' off in the to-do list", name)
  }

  /** Marks done the open action items that have been ticked off in the to-do list; returns how many. */
  fun syncCompleted(): Int = QuarkusTransaction.requiringNew().call {
    val open = items.listOpenInTodoList()
    if (open.isEmpty()) {
      return@call 0
    }
    val done = lock.withLock { list().doneNames(open.mapNotNull { it.todoName }) }
    val completed = open.filter { it.todoName in done }
    for (item in completed) {
      val email = item.email!!
      email.completeActionItem(item)
      Mdc.with(Mdc.EMAIL to email.id) { LOG.infof("'%s' was ticked off in the to-do list, so it's done", item.todoName) }
    }
    completed.size
  }

  private fun list(): TodoList =
    TodoList(Path.of(settings.getString(SettingKeys.TODO_PATH) ?: throw TodoListException("The to-do list folder isn't set")))

  /** The item's notes (or the email's summary), what the email was, and a link to it here. */
  private fun body(email: EmailEntity, item: EmailActionItemEntity): String {
    val parts = mutableListOf<String>()
    (item.notes ?: email.summary)?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    val from = email.senderName?.takeIf { it.isNotBlank() } ?: email.sender
    parts += "Email: ${email.subject ?: "(no subject)"}" + (from?.let { " from $it" } ?: "")
    settings.getString(SettingKeys.TODO_POSTMASTER_URL)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let {
      parts += "[Open the email in Postmaster]($it/emails/${email.id})"
    }
    return parts.joinToString("\n\n")
  }

  companion object {
    private val LOG = Logger.getLogger(TodoListService::class.java)
  }
}
