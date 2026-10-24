package sh.hutch.postmaster.services

import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sh.hutch.postmaster.models.Placeholders
import sh.hutch.postmaster.models.PromptInput
import sh.hutch.postmaster.repositories.PromptRepository

// REQ Prompts and "Editing or creating a prompt"; CLAR Q15 (system prompts
// can't be deleted), Q19 (markdown preview, {{placeholder}} tokens), D3
@QuarkusTest
class PromptServiceTest {
  @Inject
  lateinit var service: PromptService

  @Inject
  lateinit var prompts: PromptRepository

  @Test
  fun `name and prompt text are both required`() {
    // REQ Editing or creating a prompt 1
    val error = assertThrows<ValidationException> { service.create(PromptInput(" ", "")) }

    assertEquals(setOf("name", "contents"), error.errors.keys)
    assertEquals("My prompt", service.create(PromptInput("My prompt", "Do {{text}}")).name)
  }

  @Test
  fun `system prompts can't be deleted, but other prompts are soft-deleted`() {
    // CLAR Q15, D3
    assertThrows<OperationNotAllowedException> { service.delete(prompts.findByKey("summarise")!!.id!!) }

    val mine = service.create(PromptInput("Disposable", "text"))
    service.delete(mine.id!!)
    assertTrue(prompts.findById(mine.id!!)!!.deleted, "kept, marked deleted")
    assertTrue(service.list().none { it.id == mine.id })
  }

  @Test
  @TestTransaction
  fun `a system prompt's name and text can be edited`() {
    val summarise = prompts.findByKey("summarise")!!

    service.update(summarise.id!!, PromptInput("Summarise", "New text"))

    assertEquals("New text", prompts.findByKey("summarise")!!.contents)
  }

  @Test
  fun `the preview renders markdown and escapes raw HTML`() {
    // CLAR Q19
    val html = service.preview("# Heading\n\n**bold** {{text}}\n\n<script>alert(1)</script>")

    assertTrue(html.contains("<h1>Heading</h1>"), html)
    assertTrue(html.contains("<strong>bold</strong>"), html)
    assertTrue(html.contains("{{text}}"), html)
    assertFalse(html.contains("<script>"), html)
  }

  @Test
  fun `the placeholders are the seven in the requirements plus the types table and received date`() {
    // REQ Editing or creating a prompt 4 (1-7); CLAR D19 ({{types}}), R5 ({{received}})
    assertEquals(
        listOf(
            "{{calendars}}", "{{events}}", "{{subject}}", "{{sender}}", "{{received}}", "{{text}}", "{{html}}", "{{page}}",
            "{{types}}"
        ),
        Placeholders.ALL.map { it.token }
    )
    assertTrue(Placeholders.ALL.all { it.label.isNotBlank() })
  }
}
