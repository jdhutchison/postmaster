package sh.hutch.postmaster.models


/**
 * A setting as presented to users. [value] is always null for encrypted settings so their plain
 * text never leaves SettingsService; [displayValue] masks them instead.
 */
data class Setting(
    val key: String,
    val label: String,
    val type: SettingType,
    val allowedValues: List<String>,
    val description: String?,
    val value: String?,
    val displayValue: String,
    val hasValue: Boolean,
    val position: Int
)

/** Settings that share a key prefix (`imap.`, `llm.`, …), for the Configuration page. */
@io.quarkus.qute.TemplateData
data class SettingGroup(val key: String, val label: String, val settings: List<Setting>) {
  companion object {
    private val LABELS = mapOf(
        "processing" to "Processing",
        "imap" to "Mailbox (IMAP)",
        "smtp" to "Forwarding (SMTP)",
        "llm" to "LLM",
        "attachments" to "Attachments",
        "events" to "Events",
        "fetch" to "Fetching pages",
        "bills" to "Bills",
        "todo" to "To-do list"
    )

    /** [settings] grouped by key prefix, keeping their order. */
    fun of(settings: List<Setting>): List<SettingGroup> =
      settings.groupBy { it.key.substringBefore('.') }
          .map { (key, members) -> SettingGroup(key, LABELS[key] ?: key.replaceFirstChar { it.uppercase() }, members) }
  }
}
