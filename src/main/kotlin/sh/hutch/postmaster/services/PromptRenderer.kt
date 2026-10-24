package sh.hutch.postmaster.services

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import sh.hutch.postmaster.models.PromptContext
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.PromptRepository
import sh.hutch.postmaster.repositories.TypeRepository
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fills a prompt's {{placeholders}} (CLARIFICATIONS.md Q15, D19). */
@ApplicationScoped
@Transactional
class PromptRenderer(
    private val prompts: PromptRepository,
    private val calendars: CalendarRepository,
    private val types: TypeRepository,
    private val settings: SettingsService
) {
  private val json = ObjectMapper()

  /**
   * The prompt with key [promptKey] with its placeholders filled from [context]. Email and page
   * content is cut to llm.max_input_chars characters; unknown placeholders are left as they are.
   */
  fun render(promptKey: String, context: PromptContext): String {
    val prompt = prompts.findByKey(promptKey) ?: throw IllegalStateException("There is no prompt with key '$promptKey'")
    val limit = settings.getInt(SettingKeys.LLM_MAX_INPUT_CHARS)
    fun cut(value: String?) = value.orEmpty().let { if (it.length > limit) it.substring(0, limit) else it }
    val values = mapOf(
        "subject" to { context.subject.orEmpty() },
        "sender" to { context.sender.orEmpty() },
        "received" to {
          context.receivedAt?.let { RECEIVED.format(it.atZone(settings.getZoneId(SettingKeys.PROCESSING_TIMEZONE))) }.orEmpty()
        },
        "text" to { cut(context.text) },
        "html" to { cut(context.html) },
        "page" to { cut(context.page) },
        "calendars" to { calendars.listEnabled().joinToString("\n") { "- ${it.name}: ${it.description}" } },
        "types" to {
          types.listActive().joinToString("\n") { type ->
            if (type.description.isNullOrBlank()) "- ${type.name}" else "- ${type.name}: ${type.description}"
          }
        },
        "events" to {
          json.writeValueAsString(context.events.map {
            linkedMapOf("id" to it.id, "title" to it.title, "when" to it.`when`, "location" to it.location, "notes" to it.notes)
          })
        }
    )
    return PLACEHOLDER.replace(prompt.contents) { match ->
      values[match.groupValues[1]]?.invoke() ?: match.value
    }
  }

  companion object {
    private val PLACEHOLDER = Regex("\\{\\{(\\w+)}}")
    private val RECEIVED = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)
  }
}
