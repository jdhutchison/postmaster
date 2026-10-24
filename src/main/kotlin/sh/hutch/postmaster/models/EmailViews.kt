package sh.hutch.postmaster.models

import io.quarkus.qute.TemplateData
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

// What the emails pages and the MCP tools show (REQUIREMENTS.md "Viewing emails", "MCP server").
// They're built inside the service's transaction, so nothing is loaded lazily while rendering.

/**
 * Filters for the emails list. [typeId] is [UNCLASSIFIED] for emails without a type. Dates are
 * inclusive, in the processing.timezone setting.
 */
@TemplateData
data class EmailFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val status: EmailStatus? = null,
    val typeId: Long? = null,
    val sender: String? = null,
    val responseRequired: Boolean = false,
    val actionRequired: Boolean = false,
    val withErrors: Boolean = false,
    val page: Int = 1
) {
  /** Whether any filter is set. Unfiltered, the list shows the most recent emails only. */
  val isFiltered: Boolean
    get() = from != null || to != null || status != null || typeId != null || !sender.isNullOrBlank() ||
        responseRequired || actionRequired || withErrors

  /** How many filters are set, for the "Filters" button. */
  val activeCount: Int
    get() = listOf(
        from != null || to != null, status != null, typeId != null, !sender.isNullOrBlank(),
        responseRequired, actionRequired, withErrors
    ).count { it }

  fun hasStatus(candidate: EmailStatus): Boolean = status == candidate

  fun hasType(id: Long?): Boolean = typeId != null && typeId == id

  val unclassified: Boolean
    get() = typeId == UNCLASSIFIED

  /** The query string for [page] of these results, starting with `?`. */
  fun query(page: Int): String {
    val params = listOfNotNull(
        from?.let { "from" to it.toString() },
        to?.let { "to" to it.toString() },
        status?.let { "status" to it.code },
        typeId?.let { "type" to if (it == UNCLASSIFIED) TYPE_NONE else it.toString() },
        sender?.takeIf { it.isNotBlank() }?.let { "sender" to it },
        if (responseRequired) "response" to "true" else null,
        if (actionRequired) "action" to "true" else null,
        if (withErrors) "errors" to "true" else null,
        if (page > 1) "page" to page.toString() else null
    )
    return "?" + params.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }
  }

  companion object {
    const val UNCLASSIFIED = 0L
    const val TYPE_NONE = "none"
  }
}

/** An email in the list, with its counts. */
@TemplateData
data class EmailRow(
    val id: Long,
    val receivedAt: Instant?,
    val subject: String?,
    val sender: String?,
    val senderName: String?,
    val status: EmailStatus,
    val type: String?,
    val typeColour: String?,
    val responseRequired: Boolean,
    val actionRequired: Boolean,
    val isRead: Boolean,
    val events: Long,
    val attachments: Long,
    val errors: Long
) {
  /** The sender's name, or the address when there's no name. */
  val from: String
    get() = senderName?.takeIf { it.isNotBlank() } ?: sender.orEmpty()
}

/** A page of the emails list. [paginated] is false for the unfiltered "most recent" view. */
@TemplateData
data class EmailPage(
    val rows: List<EmailRow>,
    val filter: EmailFilter,
    val total: Long,
    val page: Int,
    val pageCount: Int,
    val paginated: Boolean
) {
  val hasPrevious: Boolean
    get() = paginated && page > 1

  val hasNext: Boolean
    get() = paginated && page < pageCount

  val previousQuery: String
    get() = filter.query(page - 1)

  val nextQuery: String
    get() = filter.query(page + 1)
}

@TemplateData
data class BillDetails(
    val biller: String?,
    val amount: BigDecimal?,
    val dueDate: LocalDate?,
    val payLink: String?,
    val downloadUrl: String?
)

/** Something an email asks the user to do (R5). [todoName] is its name in the to-do list, if it's there. */
@TemplateData
data class ActionItemView(
    val id: Long,
    val description: String,
    val dueDate: LocalDate?,
    val notes: String?,
    val completed: Boolean,
    val completedAt: Instant?,
    val todoName: String?
)

/** An event found in an email. [calendarId] is its calendar, or the suggested one if it isn't added. */
@TemplateData
data class EventView(
    val id: Long,
    val title: String,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    val isAdded: Boolean,
    val calendarId: Long?,
    val calendar: String?,
    val calendarColour: String?,
    /** When, for display: `Thu 5 Nov 2026` or `Thu 5 Nov 2026, 09:00–10:00`. */
    val time: String
)

@TemplateData
data class AttachmentView(
    val id: Long,
    val filename: String,
    val contentType: String?,
    val sizeBytes: Long?,
    val downloaded: Boolean,
    /** `pdf`, `image` or `text` when it's downloaded and can be shown in the browser. */
    val preview: String?
)

@TemplateData
data class ActionResultView(
    val actionType: String,
    /** How the action is named in the UI, e.g. "Mark read". */
    val label: String,
    val source: ActionSource,
    val status: ActionResultStatus,
    val detail: String?,
    val executedAt: Instant?
)

/** A rule that matched an email and the results of the actions it ran. */
@TemplateData
data class RuleResults(val ruleId: Long?, val rule: String, val ruleType: RuleType, val results: List<ActionResultView>)

@TemplateData
data class ErrorView(val phase: ErrorPhase, val message: String, val createdAt: Instant?)

@TemplateData
data class CalendarOption(val id: Long, val name: String, val colour: String)

/**
 * An email in full, for its detail page: the matching rules with their actions, the actions run by
 * hand (from the UI or MCP), and the calendars an event can be put in.
 */
@TemplateData
data class EmailDetail(
    val id: Long,
    val messageId: String,
    val subject: String?,
    val sender: String?,
    val senderName: String?,
    val receivedAt: Instant?,
    val processedAt: Instant?,
    val status: EmailStatus,
    val typeId: Long?,
    val type: String?,
    val typeColour: String?,
    val folder: String?,
    val movedTo: String?,
    val isRead: Boolean,
    val summary: String?,
    val link: String?,
    val linkSummary: String?,
    val linkContents: String?,
    val message: String?,
    val responseRequired: Boolean,
    val actionRequired: Boolean,
    val actionDescription: String?,
    val actionItems: List<ActionItemView>,
    val bill: BillDetails?,
    val events: List<EventView>,
    val attachments: List<AttachmentView>,
    val classification: List<RuleResults>,
    val actions: List<RuleResults>,
    val manual: List<ActionResultView>,
    val errors: List<ErrorView>,
    val calendars: List<CalendarOption>
) {
  val from: String
    get() = listOfNotNull(senderName?.takeIf { it.isNotBlank() }, sender?.let { "<$it>" }).joinToString(" ")

  fun event(id: Long): EventView? = events.firstOrNull { it.id == id }

  fun attachment(id: Long): AttachmentView? = attachments.firstOrNull { it.id == id }
}

/** What a manual action on an email (from the UI or MCP) did. */
data class ManualResult(val ok: Boolean, val message: String)
