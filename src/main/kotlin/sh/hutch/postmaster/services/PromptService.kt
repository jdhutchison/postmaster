package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import sh.hutch.postmaster.entities.PromptEntity
import sh.hutch.postmaster.models.PromptInput
import sh.hutch.postmaster.repositories.PromptRepository

/** Manages the prompts used with the LLM. System prompts can be edited but not deleted (Q15). */
@ApplicationScoped
@Transactional
class PromptService(private val prompts: PromptRepository) {
  private val parser = Parser.builder().build()
  private val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()

  fun list(): List<PromptEntity> = prompts.listActive()

  fun get(id: Long): PromptEntity = prompts.findActive(id) ?: throw NotFoundException("Prompt $id not found")

  fun create(input: PromptInput): PromptEntity {
    val prompt = PromptEntity()
    apply(prompt, input)
    prompts.persist(prompt)
    return prompt
  }

  fun update(id: Long, input: PromptInput): PromptEntity {
    val prompt = get(id)
    apply(prompt, input)
    return prompt
  }

  fun delete(id: Long): PromptEntity {
    val prompt = get(id)
    if (prompt.isSystem) {
      throw OperationNotAllowedException("${prompt.name} is a system prompt and can't be deleted")
    }
    prompt.markDeleted()
    return prompt
  }

  /** The markdown rendered as HTML, with any raw HTML in it escaped. */
  @Transactional(Transactional.TxType.NOT_SUPPORTED)
  fun preview(markdown: String): String = renderer.render(parser.parse(markdown))

  private fun apply(prompt: PromptEntity, input: PromptInput) {
    val errors = Errors()
    val name = errors.required("name", input.name, "Name", MAX_NAME)
    if (input.contents.isNullOrBlank()) {
      errors.add("contents", "Prompt text is required")
    }
    errors.throwIfAny()
    prompt.name = name!!
    prompt.contents = input.contents!!
  }

  companion object {
    const val MAX_NAME = 100
  }
}
