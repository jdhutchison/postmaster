package sh.hutch.postmaster.services

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
 * The markdown to-do list in a folder (CLARIFICATIONS.md R5). Each item has a file named after it
 * with front matter (`todo-item`, `tags`, `created`, `completed` and an optional `due`), and
 * `index.md` lists every item as a task, `- [ ] [[name]] #todo-item`, above its
 * `<!-- todo-items:end -->` line. Files are replaced atomically. Not thread safe: TodoListService
 * serialises access.
 */
class TodoList(private val root: Path) {

  /**
   * Writes a file for [entry] and lists it in the index; returns the file's name without `.md`. The
   * name is the title made safe for a file name, numbered if the file already exists.
   *
   * @throws TodoListException if the index has no end line, or a file can't be read or written
   */
  fun add(entry: TodoEntry): String = io("add '${entry.title}' to") {
    Files.createDirectories(root)
    val index = readIndex() ?: Text(mutableListOf(END_MARKER), "\n", true)
    val end = index.lines.indexOfLast { it.trim() == END_MARKER }
    if (end < 0) {
      throw TodoListException("$INDEX in $root has no $END_MARKER line")
    }
    val name = uniqueName(entry.title, index.lines)
    val file = root.resolve("$name.md")
    Files.writeString(file, content(entry), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
      index.lines.add(end, "- [ ] [[$name]] $TAG_TEXT")
      write(root.resolve(INDEX), index)
    } catch (e: Exception) {
      Files.deleteIfExists(file)
      throw e
    }
    name
  }

  /**
   * Ticks the item off: its task in the index becomes `- [x]` and its front matter gets
   * `completed: true`. A missing file or task is left alone.
   */
  fun complete(name: String): Unit = io("tick '$name' off in") {
    readIndex()?.let { index ->
      val task = task(name, done = false)
      val at = index.lines.indexOfFirst { task.matches(it) }
      if (at >= 0) {
        index.lines[at] = task.replace(index.lines[at], "$1[x]$2")
        write(root.resolve(INDEX), index)
      }
    }
    val file = root.resolve("$name.md")
    read(file)?.let { text ->
      val close = frontMatterEnd(text.lines)
      if (close > 0) {
        val at = (1 until close).firstOrNull { COMPLETED.matches(text.lines[it]) }
        if (at == null) {
          text.lines.add(close, "completed: true")
        } else {
          text.lines[at] = "completed: true"
        }
        write(file, text)
      }
    }
  }

  /** Which of [names] are done: ticked in the index, or with `completed: true` in their front matter. */
  fun doneNames(names: Collection<String>): Set<String> = io("read") {
    val index = readIndex()?.lines.orEmpty()
    names.filter { name ->
      val ticked = task(name, done = true)
      index.any { ticked.matches(it) } || completedInFile(name)
    }.toSet()
  }

  private fun completedInFile(name: String): Boolean {
    val lines = read(root.resolve("$name.md"))?.lines ?: return false
    val close = frontMatterEnd(lines)
    return (1 until maxOf(close, 1)).any { lines[it].trim().equals("completed: true", ignoreCase = true) }
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
    private val COMPLETED = Regex("^completed\\s*:.*$", RegexOption.IGNORE_CASE)

    /** Characters that can't be in a file name on some systems, or that break a `[[link]]`. */
    private val UNSAFE = Regex("[\\x00-\\x1f\\\\/:*?\"<>|#^\\[\\]]")

    /** A file name, without `.md`, for an item called [title]. */
    fun fileName(title: String): String {
      val cleaned = title.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
      val cut = if (cleaned.length > MAX_NAME) cleaned.take(MAX_NAME).trim().trimEnd('.') else cleaned
      return cut.ifEmpty { "Action item" }
    }

    /** A task line for [name] in the index: `- [ ] [[name]]`, or ticked when [done]. */
    private fun task(name: String, done: Boolean): Regex =
      Regex("^(\\s*[-*+] )\\[${if (done) "[xX]" else " "}\\]( \\[\\[${Regex.escape(name)}(?:\\|[^\\]]*)?\\]\\].*)$")

    /** The index of the line closing the front matter, or -1 if there's none. */
    private fun frontMatterEnd(lines: List<String>): Int {
      if (lines.isEmpty() || lines[0].trim() != "---") {
        return -1
      }
      return (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: -1
    }
  }
}
