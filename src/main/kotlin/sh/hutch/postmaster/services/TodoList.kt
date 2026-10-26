package sh.hutch.postmaster.services

import sh.hutch.postmaster.models.TodoStatus
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A new item for the to-do list: [title] names its file, and [body] follows the front matter. */
data class TodoEntry(val title: String, val created: LocalDateTime, val due: LocalDate?, val body: String?)

/**
 * An item's status as the to-do list gives it: its task's box in the index ([index]), and its front
 * matter's `status:` ([status]) and `completed:` ([completed]). Each is null where there's none.
 */
data class TodoObserved(val index: TodoStatus?, val status: TodoStatus?, val completed: Boolean?)

/**
 * The markdown to-do list in a folder (CLARIFICATIONS.md R5, R9). Each item has a file named after
 * it with front matter (`todo-item`, `tags`, `created`, `completed`, `status` and an optional `due`),
 * and `index.md` lists every item as a task, `- [ ] [[name]] #todo-item`, above its
 * `<!-- todo-items:end -->` line. A task is `- [ ]` while open, `- [/]` in progress and `- [x]`
 * done, and the front matter's `status` is `todo`, `in-progress` or `done` to match. Files are
 * replaced atomically. Not thread safe: TodoListService serialises access.
 */
class TodoList(private val root: Path) {

  /**
   * Writes a file for [entry] and lists it in the index; returns the file's name without `.md`. The
   * name is the title made safe for a file name, numbered if the file already exists.
   *
   * @throws TodoListException if the index has no end line, or a file can't be read or written
   */
  fun add(entry: TodoEntry): String {
    val name = reserveName(entry.title)
    write(name, entry)
    return name
  }

  /**
   * A name for an item called [title] that no file or task uses yet, without writing anything.
   * Saving it before [write] lets an interrupted write be finished under the same name
   * (CLARIFICATIONS.md R6).
   *
   * @throws TodoListException if the index has no end line or can't be read
   */
  fun reserveName(title: String): String = io("name '$title' in") {
    uniqueName(title, endedIndex().lines)
  }

  /**
   * Writes the item [name] for [entry]: its file, unless it's there already, and its task in the
   * index, unless it's listed already. Writing it again finishes an interrupted write.
   *
   * @throws TodoListException if the index has no end line, or a file can't be read or written
   */
  fun write(name: String, entry: TodoEntry): Unit = io("add '$name' to") {
    Files.createDirectories(root)
    val index = endedIndex()
    val file = root.resolve("$name.md")
    if (!Files.exists(file)) {
      Files.writeString(file, content(entry), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    if (index.lines.none { it.contains("[[$name]]") || it.contains("[[$name|") }) {
      index.lines.add(index.lines.indexOfLast { it.trim() == END_MARKER }, "- [ ] [[$name]] $TAG_TEXT")
      write(root.resolve(INDEX), index)
    }
  }

  /** The index, or a new one if there's none yet; it must have the end line. */
  private fun endedIndex(): Text {
    val index = readIndex() ?: Text(mutableListOf(END_MARKER), "\n", true)
    if (index.lines.none { it.trim() == END_MARKER }) {
      throw TodoListException("$INDEX in $root has no $END_MARKER line")
    }
    return index
  }

  /** Ticks the item off: `- [x]` in the index and `status: done`, `completed: true` in its file. */
  fun complete(name: String) = setStatus(name, TodoStatus.DONE)

  /**
   * Marks the item open, in progress or done (CLARIFICATIONS.md R9): its task in the index becomes
   * `- [ ]`, `- [/]` or `- [x]`, and its front matter gets `status:` and `completed:` to match. A
   * missing file or task is left alone.
   */
  fun setStatus(name: String, status: TodoStatus): Unit = io("mark '$name' ${status.code} in") {
    readIndex()?.let { index ->
      val task = task(name)
      val at = index.lines.indexOfFirst { task.matches(it) }
      if (at >= 0) {
        val line = task.replace(index.lines[at], "$1[${BOXES.getValue(status)}]$3")
        if (line != index.lines[at]) {
          index.lines[at] = line
          write(root.resolve(INDEX), index)
        }
      }
    }
    val file = root.resolve("$name.md")
    read(file)?.let { text ->
      if (frontMatterEnd(text.lines) > 0) {
        val changed = setField(text.lines, COMPLETED, "completed: ${status == TodoStatus.DONE}") or
            setField(text.lines, STATUS, "status: ${status.code}")
        if (changed) {
          write(file, text)
        }
      }
    }
  }

  /**
   * Sets the front matter field matching [field] to [line], adding it before the closing `---` if
   * it isn't there; whether anything changed.
   */
  private fun setField(lines: MutableList<String>, field: Regex, line: String): Boolean {
    val close = frontMatterEnd(lines)
    val at = (1 until close).firstOrNull { field.matches(lines[it]) }
    return when {
      at == null -> {
        lines.add(close, line)
        true
      }
      lines[at] == line -> false
      else -> {
        lines[at] = line
        true
      }
    }
  }

  /**
   * What the index and each file say about [names]: the task's box and the front matter's
   * `status:` and `completed:`, each null where there's no task, file or field, or it can't be read.
   */
  fun statuses(names: Collection<String>): Map<String, TodoObserved> = io("read") {
    val index = readIndex()?.lines.orEmpty()
    names.associateWith { name ->
      val task = task(name)
      val box = index.firstNotNullOfOrNull { task.matchEntire(it)?.groupValues?.get(2) }
      val fields = frontMatter(name)
      TodoObserved(
          box?.let { boxStatus(it) },
          fields[STATUS]?.let { STATUS_WORDS[it.replace(' ', '-').replace('_', '-')] },
          fields[COMPLETED]?.let { if (it == "true") true else if (it == "false") false else null }
      )
    }
  }

  /** Which of [names] are done: ticked in the index, or done in their front matter. */
  fun doneNames(names: Collection<String>): Set<String> =
    statuses(names).filterValues { it.index == TodoStatus.DONE || it.status == TodoStatus.DONE || it.completed == true }.keys

  /**
   * Takes the item out of the list: deletes its file and its task in the index (CLARIFICATIONS.md
   * R9). Either that's already gone is fine.
   */
  fun remove(name: String): Unit = io("remove '$name' from") {
    readIndex()?.let { index ->
      val task = task(name)
      if (index.lines.removeIf { task.matches(it) }) {
        write(root.resolve(INDEX), index)
      }
    }
    Files.deleteIfExists(root.resolve("$name.md"))
  }

  /** The `status:` and `completed:` values in the item's front matter, lower-cased, by field. */
  private fun frontMatter(name: String): Map<Regex, String> {
    val lines = read(root.resolve("$name.md"))?.lines ?: return emptyMap()
    val close = frontMatterEnd(lines)
    return listOf(STATUS, COMPLETED).mapNotNull { field ->
      (1 until maxOf(close, 1)).firstNotNullOfOrNull { field.matchEntire(lines[it])?.groupValues?.get(1) }
          ?.trim()?.trim('"', '\'')?.lowercase()?.let { field to it }
    }.toMap()
  }

  /**
   * A name for [title] that no file and no index line uses: an old task, even one whose file has
   * gone, would otherwise be taken for the new item's.
   */
  private fun uniqueName(title: String, index: List<String>): String {
    val base = fileName(title)
    var name = base
    var n = 2
    while (Files.exists(root.resolve("$name.md")) || index.any { it.contains("[[$name]]") || it.contains("[[$name|") }) {
      name = "$base $n"
      n++
    }
    return name
  }

  private fun content(entry: TodoEntry): String = buildString {
    append("---\n")
    append("todo-item: true\n")
    append("tags:\n")
    append("  - $TAG\n")
    append("created: ${CREATED.format(entry.created)}\n")
    append("completed: false\n")
    append("status: ${TodoStatus.OPEN.code}\n")
    entry.due?.let { append("due: $it\n") }
    append("---\n")
    entry.body?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n").append(it).append("\n") }
  }

  /** The text of a file split into lines, remembering its line separator; null if it doesn't exist. */
  private class Text(val lines: MutableList<String>, val separator: String, val finalNewline: Boolean)

  private fun readIndex(): Text? = read(root.resolve(INDEX))

  private fun read(file: Path): Text? {
    if (!Files.isRegularFile(file)) {
      return null
    }
    val text = Files.readString(file)
    val separator = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split(separator).toMutableList()
    val finalNewline = text.endsWith(separator)
    if (finalNewline) {
      lines.removeAt(lines.size - 1)
    }
    return Text(lines, separator, finalNewline)
  }

  private fun write(file: Path, text: Text) {
    val contents = text.lines.joinToString(text.separator) + if (text.finalNewline) text.separator else ""
    val temp = Files.createTempFile(file.parent, ".${file.fileName}.", ".tmp")
    try {
      Files.writeString(temp, contents)
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (e: AtomicMoveNotSupportedException) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  private fun <T> io(what: String, block: () -> T): T =
    try {
      block()
    } catch (e: IOException) {
      throw TodoListException("Couldn't $what the to-do list in $root: ${e.message ?: e.javaClass.simpleName}", e)
    }

  companion object {
    const val INDEX = "index.md"
    const val END_MARKER = "<!-- todo-items:end -->"
    const val TAG = "todo-item"
    private const val TAG_TEXT = "#$TAG"
    private const val MAX_NAME = 120
    private val CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val COMPLETED = Regex("^completed\\s*:(.*)$", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("^status\\s*:(.*)$", RegexOption.IGNORE_CASE)

    /** The box of a task in each status. */
    private val BOXES = mapOf(TodoStatus.OPEN to " ", TodoStatus.IN_PROGRESS to "/", TodoStatus.DONE to "x")

    /** Words a `status:` field may use. */
    private val STATUS_WORDS = mapOf(
        "todo" to TodoStatus.OPEN, "to-do" to TodoStatus.OPEN, "open" to TodoStatus.OPEN,
        "in-progress" to TodoStatus.IN_PROGRESS, "doing" to TodoStatus.IN_PROGRESS, "started" to TodoStatus.IN_PROGRESS,
        "done" to TodoStatus.DONE, "completed" to TodoStatus.DONE, "complete" to TodoStatus.DONE,
        "cancelled" to TodoStatus.DONE, "canceled" to TodoStatus.DONE
    )

    /**
     * A task's box as a status: ` ` open, `/` in progress, `x` done, and `-` (cancelled) done too;
     * null for any other mark.
     */
    private fun boxStatus(box: String): TodoStatus? =
      when (box) {
        " " -> TodoStatus.OPEN
        "/" -> TodoStatus.IN_PROGRESS
        "x", "X", "-" -> TodoStatus.DONE
        else -> null
      }

    /** Characters that can't be in a file name on some systems, or that break a `[[link]]`. */
    private val UNSAFE = Regex("[\\x00-\\x1f\\\\/:*?\"<>|#^\\[\\]]")

    /** A file name, without `.md`, for an item called [title]. */
    fun fileName(title: String): String {
      val cleaned = title.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
      val cut = if (cleaned.length > MAX_NAME) cleaned.take(MAX_NAME).trim().trimEnd('.') else cleaned
      return cut.ifEmpty { "Action item" }
    }

    /** A task line for [name] in the index, `- [ ] [[name]]`, with any mark in its box (group 2). */
    private fun task(name: String): Regex =
      Regex("^(\\s*[-*+] )\\[(.)\\]( \\[\\[${Regex.escape(name)}(?:\\|[^\\]]*)?\\]\\].*)$")

    /** The index of the line closing the front matter, or -1 if there's none. */
    private fun frontMatterEnd(lines: List<String>): Int {
      if (lines.isEmpty() || lines[0].trim() != "---") {
        return -1
      }
      return (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: -1
    }
  }
}
