package sh.hutch.postmaster.services

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import sh.hutch.postmaster.entities.EmailActionItemEntity
import sh.hutch.postmaster.entities.EmailEntity
import sh.hutch.postmaster.models.TodoStatus
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
 * (CLARIFICATIONS.md R5, R9): writes items to it, ticks them off when they're done here, removes
 * those that become events, and brings in the status (open, in progress or done) they've been
 * given there. One change to the files at a time.
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
    val name = lock.withLock {
      val list = list()
      // The name is saved before the files are written, so if processing is interrupted after
      // writing them the item isn't written again under another name (R6)
      val name = item.todoName ?: list.reserveName(item.description).also { reserve(item, it) }
      list.write(name, entry)
      name
    }
    item.todoCreatedAt = now
    LOG.infof("Added '%s' to the to-do list", name)
    return name
  }

  /** Saves [name] against [item] straight away, in a transaction of its own. */
  private fun reserve(item: EmailActionItemEntity, name: String) {
    item.todoName = name
    val id = item.id ?: return
    // An item that isn't saved yet (only in dev data) can't be found from another transaction
    QuarkusTransaction.requiringNew().run { items.findById(id)?.todoName = name }
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

  /** What the to-do list says about the items named [names], for the status check. */
  fun observe(names: Collection<String>): Map<String, TodoObserved> = lock.withLock { list().statuses(names) }

  /**
   * Takes [item] out of the to-do list, if it's there: its file and its task (CLARIFICATIONS.md R9).
   *
   * @throws TodoListException if the list can't be written
   */
  fun remove(item: EmailActionItemEntity) {
    val name = item.todoName ?: return
    lock.withLock { list().remove(name) }
    LOG.infof("Removed '%s' from the to-do list", name)
  }

  /**
   * Brings the status of each action item in the to-do list in from there (CLARIFICATIONS.md R9),
   * and returns how many changed. Where the index's task or the file's front matter has changed
   * since Postmaster last saw it, the item takes that status (the index's if both have), and the
   * other is updated to match.
   */
  fun sync(): Int = QuarkusTransaction.requiringNew().call {
    val listed = items.listInTodoList()
    if (listed.isEmpty()) {
      return@call 0
    }
    lock.withLock {
      val list = list()
      val observed = list.statuses(listed.mapNotNull { it.todoName })
      var changed = 0
      for (item in listed) {
        val name = item.todoName!!
        val seen = observed[name] ?: continue
        val known = item.status
        val status = reconcile(known, seen)
        val email = item.email!!
        if (status != known) {
          email.setActionItemStatus(item, status)
          changed++
          Mdc.with(Mdc.EMAIL to email.id) { LOG.infof("'%s' is %s in the to-do list", name, status.code) }
        }
        if (!agrees(seen, status)) {
          try {
            list.setStatus(name, status)
          } catch (e: TodoListException) {
            LOG.warnf("'%s' couldn't be marked %s in the to-do list: %s", name, status.code, e.message)
          }
        }
      }
      changed
    }
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

    /**
     * The status an item now has, from [known] (what Postmaster last saw) and what the to-do list
     * says: the index's task if it has changed, else the front matter's `status:`, else its
     * `completed:` (where `false` fits open and in progress alike).
     */
    fun reconcile(known: TodoStatus, seen: TodoObserved): TodoStatus =
      when {
        seen.index != null && seen.index != known -> seen.index
        seen.status != null && seen.status != known -> seen.status
        seen.completed != null && seen.completed != (known == TodoStatus.DONE) ->
          if (seen.completed) TodoStatus.DONE else TodoStatus.OPEN
        else -> known
      }

    /** Whether everything the to-do list says about the item fits [status]. */
    fun agrees(seen: TodoObserved, status: TodoStatus): Boolean =
      (seen.index == null || seen.index == status) && (seen.status == null || seen.status == status) &&
          (seen.completed == null || seen.completed == (status == TodoStatus.DONE))
  }
}
