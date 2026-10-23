package sh.hutch.postmaster.models

import io.quarkus.qute.TemplateData

/** A placeholder that can be put in a prompt template. */
@TemplateData
data class Placeholder(val token: String, val label: String)

/** The placeholders prompts can use (REQUIREMENTS.md "Editing or creating a prompt", plus {{types}}, D19). */
object Placeholders {
  val CALENDARS = Placeholder("{{calendars}}", "Enabled calendars and their purposes")
  val EVENTS = Placeholder("{{events}}", "Events found in the email")
  val SUBJECT = Placeholder("{{subject}}", "Email subject")
  val SENDER = Placeholder("{{sender}}", "Email sender")
  val TEXT = Placeholder("{{text}}", "Plain text of the email")
  val HTML = Placeholder("{{html}}", "HTML of the email")
  val PAGE = Placeholder("{{page}}", "Contents of the page linked from the email")
  val TYPES = Placeholder("{{types}}", "Email types and their descriptions")

  val ALL: List<Placeholder> = listOf(CALENDARS, EVENTS, SUBJECT, SENDER, TEXT, HTML, PAGE, TYPES)
}
