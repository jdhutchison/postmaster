package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import sh.hutch.postmaster.models.TodoStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The markdown to-do list (CLAR R5): a file per action item with front matter,
 * and a task for each in index.md above its `<!-- todo-items:end -->` line.
 */
class TodoListTest {
  @TempDir
  lateinit var root: Path

  private val created = LocalDateTime.of(2026, 10, 25, 14, 3, 12)

  private fun list() = TodoList(root)

  private fun index(text: String) = Files.writeString(root.resolve("index.md"), text)

  private fun read(name: String) = Files.readString(root.resolve(name))

  @Test
  fun `an item gets a file named after it, with front matter, notes and a link`() {
    index("<!-- todo-items:end -->\n")

    val name = list().add(TodoEntry("Post turntable in three days", created, LocalDate.of(2026, 10, 28), "Notes\n\n[Open](http://x/emails/1)"))

    assertEquals("Post turntable in three days", name)
    assertEquals(
        """
        ---
        todo-item: true
        tags:
          - todo-item
        created: 2026-10-25T14:03:12
        completed: false
        status: todo
        due: 2026-10-28
        ---

        Notes

        [Open](http://x/emails/1)

        """.trimIndent(),
        read("Post turntable in three days.md")
    )
  }

  @Test
  fun `without a due date the front matter has no due line`() {
    index("<!-- todo-items:end -->\n")

    list().add(TodoEntry("Book a time", created, null, null))

    assertEquals(
        "---\ntodo-item: true\ntags:\n  - todo-item\ncreated: 2026-10-25T14:03:12\ncompleted: false\nstatus: todo\n---\n",
        read("Book a time.md")
    )
  }

  @Test
  fun `the index lists the item just above the end line`() {
    index("# To-do\n\n- [x] [[Old]] #todo-item\n<!-- todo-items:end -->\n\nMore notes\n")

    list().add(TodoEntry("Sign the form", created, null, null))
    list().add(TodoEntry("Pay", created, null, null))

    assertEquals(
        "# To-do\n\n- [x] [[Old]] #todo-item\n- [ ] [[Sign the form]] #todo-item\n- [ ] [[Pay]] #todo-item\n<!-- todo-items:end -->\n\nMore notes\n",
        read("index.md")
    )
  }

  @Test
  fun `the index keeps its Windows line endings`() {
    index("# To-do\r\n<!-- todo-items:end -->\r\n")

    list().add(TodoEntry("Pay", created, null, null))

    assertEquals("# To-do\r\n- [ ] [[Pay]] #todo-item\r\n<!-- todo-items:end -->\r\n", read("index.md"))
  }

  @Test
  fun `a missing index is created`() {
    list().add(TodoEntry("Pay", created, null, null))

    assertEquals("- [ ] [[Pay]] #todo-item\n<!-- todo-items:end -->\n", read("index.md"))
  }

  @Test
  fun `an index without the end line is refused, and no file is left behind`() {
    index("# To-do\n- [ ] [[Something]]\n")

    val e = assertThrows<TodoListException> { list().add(TodoEntry("Pay", created, null, null)) }

    assertTrue(e.message!!.contains("<!-- todo-items:end -->"), e.message)
    assertFalse(Files.exists(root.resolve("Pay.md")))
    assertEquals("# To-do\n- [ ] [[Something]]\n", read("index.md"))
  }

  @Test
  fun `names are made safe for files and links, and numbered when taken`() {
    index("<!-- todo-items:end -->\n")

    assertEquals("Pay 35 by 30 10", list().add(TodoEntry("Pay #35 by 30/10?", created, null, null)))
    assertEquals("Pay 35 by 30 10 2", list().add(TodoEntry("Pay #35 by 30/10", created, null, null)))
    assertEquals("Action item", list().add(TodoEntry("[[ ]]", created, null, null)))
    assertEquals("Read the notes", TodoList.fileName("...Read the [notes]."))
    assertEquals(120, TodoList.fileName("x".repeat(300)).length)
  }

  @Test
  fun `a name an old task still uses isn't reused, even when its file has gone`() {
    // Otherwise the old ticked task would mark the new item done
    index("- [x] [[Reply to Sam]] #todo-item\n- [ ] [[Book|alias]]\n<!-- todo-items:end -->\n")

    assertEquals("Reply to Sam 2", list().add(TodoEntry("Reply to Sam", created, null, null)))
    assertEquals("Book 2", list().add(TodoEntry("Book", created, null, null)))
    assertEquals(emptySet<String>(), list().doneNames(listOf("Reply to Sam 2")))
  }

  @Test
  fun `writing an item again finishes an interrupted write without doubling it`() {
    // CLAR R6: the name is saved first, so a write cut short can be done again
    index("<!-- todo-items:end -->\n")
    val name = list().reserveName("Pay")
    Files.writeString(root.resolve("Pay.md"), "---\ntodo-item: true\ncompleted: false\n---\n")

    list().write(name, TodoEntry("Pay", created, null, "Notes"))
    list().write(name, TodoEntry("Pay", created, null, "Notes"))

    assertEquals("- [ ] [[Pay]] #todo-item\n<!-- todo-items:end -->\n", read("index.md"))
    assertEquals("---\ntodo-item: true\ncompleted: false\n---\n", read("Pay.md"), "the file there is kept")
  }

  @Test
  fun `completing an item ticks it in the index and sets completed and status in its front matter`() {
    index("- [ ] [[Pay]] #todo-item\n- [ ] [[Pay twice]] #todo-item\n<!-- todo-items:end -->\n")
    Files.writeString(root.resolve("Pay.md"), "---\ntodo-item: true\ncompleted: false\ndue: 2026-10-28\n---\n\nNotes\ncompleted: false\n")

    list().complete("Pay")

    assertEquals("- [x] [[Pay]] #todo-item\n- [ ] [[Pay twice]] #todo-item\n<!-- todo-items:end -->\n", read("index.md"))
    assertEquals("---\ntodo-item: true\ncompleted: true\ndue: 2026-10-28\nstatus: done\n---\n\nNotes\ncompleted: false\n", read("Pay.md"))
  }

  @Test
  fun `completing an item that has gone from the list is harmless`() {
    index("<!-- todo-items:end -->\n")

    list().complete("Gone")

    assertEquals("<!-- todo-items:end -->\n", read("index.md"))
    assertFalse(Files.exists(root.resolve("Gone.md")))
  }

  @Test
  fun `an item is done when it's ticked in the index or completed in its file`() {
    index("- [x] [[Ticked]] #todo-item\n- [X] [[Ticked too|alias]]\n- [ ] [[Open]] #todo-item\n- [ ] [[Completed]]\n<!-- todo-items:end -->\n")
    Files.writeString(root.resolve("Completed.md"), "---\ntodo-item: true\ncompleted: true\n---\n")
    Files.writeString(root.resolve("Open.md"), "---\ntodo-item: true\ncompleted: false\n---\n")

    val done = list().doneNames(listOf("Ticked", "Ticked too", "Open", "Completed", "Missing"))

    assertEquals(setOf("Ticked", "Ticked too", "Completed"), done)
  }

  @Test
  fun `nothing is done when there's no list yet`() {
    assertEquals(emptySet<String>(), TodoList(root.resolve("none")).doneNames(listOf("Pay")))
  }

  // CLAR R9: open, in progress or done, in the index and the front matter, kept in step

  @Test
  fun `an item can be marked in progress and open again, in the index and its file`() {
    index("- [ ] [[Pay]] #todo-item\n<!-- todo-items:end -->\n")
    list().add(TodoEntry("Sign", created, null, null))

    list().setStatus("Pay", TodoStatus.IN_PROGRESS)
    list().setStatus("Sign", TodoStatus.IN_PROGRESS)

    assertEquals("- [/] [[Pay]] #todo-item\n- [/] [[Sign]] #todo-item\n<!-- todo-items:end -->\n", read("index.md"))
    assertTrue(read("Sign.md").contains("completed: false\nstatus: in-progress\n"), read("Sign.md"))

    list().setStatus("Sign", TodoStatus.OPEN)

    assertTrue(read("index.md").contains("- [ ] [[Sign]]"))
    assertTrue(read("Sign.md").contains("completed: false\nstatus: todo\n"), read("Sign.md"))
  }

  @Test
  fun `statuses come from the index's boxes and the front matter's status and completed fields`() {
    index("- [/] [[Started]]\n- [x] [[Ticked]]\n- [-] [[Cancelled]]\n- [ ] [[Open]]\n- [?] [[Odd]]\n<!-- todo-items:end -->\n")
    Files.writeString(root.resolve("Open.md"), "---\nstatus: In Progress\ncompleted: false\n---\n")
    Files.writeString(root.resolve("Ticked.md"), "---\ncompleted: true\n---\n")

    val seen = list().statuses(listOf("Started", "Ticked", "Cancelled", "Open", "Odd", "Missing"))

    assertEquals(TodoObserved(TodoStatus.IN_PROGRESS, null, null), seen["Started"])
    assertEquals(TodoObserved(TodoStatus.DONE, null, true), seen["Ticked"])
    assertEquals(TodoStatus.DONE, seen["Cancelled"]!!.index)
    assertEquals(TodoObserved(TodoStatus.OPEN, TodoStatus.IN_PROGRESS, false), seen["Open"])
    assertEquals(TodoObserved(null, null, null), seen["Odd"])
    assertEquals(TodoObserved(null, null, null), seen["Missing"])
  }

  @Test
  fun `removing an item deletes its file and its task, and the rest stays`() {
    index("# To-do\n- [ ] [[Pay]] #todo-item\n- [ ] [[Pay twice]] #todo-item\n<!-- todo-items:end -->\n")
    Files.writeString(root.resolve("Pay.md"), "---\ncompleted: false\n---\n")

    list().remove("Pay")
    list().remove("Gone")

    assertEquals("# To-do\n- [ ] [[Pay twice]] #todo-item\n<!-- todo-items:end -->\n", read("index.md"))
    assertFalse(Files.exists(root.resolve("Pay.md")))
  }

  @Test
  fun `a change in either place wins, the index first, and the other is brought into step`() {
    fun seen(index: TodoStatus?, status: TodoStatus?, completed: Boolean?) = TodoObserved(index, status, completed)

    // Nothing has changed
    assertEquals(TodoStatus.OPEN, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.OPEN, TodoStatus.OPEN, false)))
    assertEquals(TodoStatus.IN_PROGRESS, TodoListService.reconcile(TodoStatus.IN_PROGRESS, seen(null, null, false)))
    // Ticked or started in the index
    assertEquals(TodoStatus.DONE, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.DONE, TodoStatus.OPEN, false)))
    assertEquals(TodoStatus.IN_PROGRESS, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.IN_PROGRESS, null, null)))
    // Changed in the file only: status, or completed as before R9
    assertEquals(TodoStatus.IN_PROGRESS, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.OPEN, TodoStatus.IN_PROGRESS, false)))
    assertEquals(TodoStatus.DONE, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.OPEN, null, true)))
    // Reopened
    assertEquals(TodoStatus.OPEN, TodoListService.reconcile(TodoStatus.DONE, seen(TodoStatus.OPEN, TodoStatus.DONE, true)))
    // Both changed: the index wins
    assertEquals(TodoStatus.DONE, TodoListService.reconcile(TodoStatus.OPEN, seen(TodoStatus.DONE, TodoStatus.IN_PROGRESS, false)))

    assertTrue(TodoListService.agrees(seen(TodoStatus.IN_PROGRESS, TodoStatus.IN_PROGRESS, false), TodoStatus.IN_PROGRESS))
    assertFalse(TodoListService.agrees(seen(TodoStatus.DONE, TodoStatus.OPEN, false), TodoStatus.DONE))
    assertTrue(TodoListService.agrees(seen(null, null, null), TodoStatus.DONE))
  }
}
