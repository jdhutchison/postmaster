package sh.hutch.postmaster.models

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

// Values exchanged with the outside world: the mailbox, the LLM, web pages and khal

/** A message in the inbox, as listed before it is fetched. */
data class MailboxMessage(
    val uid: Long,
    val uidValidity: Long,
    val messageId: String,
    val receivedAt: Instant,
    val subject: String?,
    val sender: String?
)

/** An attachment part of a message; [partPath] is the 1-based dotted path through the MIME tree. */
data class AttachmentPart(val filename: String, val contentType: String, val size: Long, val partPath: String)

/** A message fetched and parsed from the mailbox. */
data class ParsedMessage(
    val uid: Long,
    val uidValidity: Long,
    val folder: String,
    val messageId: String,
    val receivedAt: Instant,
    val subject: String?,
    val sender: String?,
    val senderName: String?,
    val recipients: List<String>,
    val text: String?,
    val html: String?,
    val attachments: List<AttachmentPart>,
    val isRead: Boolean
)

/** An event as given to the prompts' {{events}} placeholder. */
data class PromptEvent(val id: Long, val title: String, val `when`: String, val location: String?, val notes: String?)

/** The values for a prompt's placeholders. */
data class PromptContext(
    val subject: String? = null,
    val sender: String? = null,
    val text: String? = null,
    val html: String? = null,
    val page: String? = null,
    val events: List<PromptEvent> = emptyList(),
    val receivedAt: Instant? = null
)

/** A link in an HTML document. */
data class Link(val text: String, val href: String)

/** A file downloaded with curl. */
data class DownloadedFile(val path: Path, val contentType: String?, val size: Long)

/** A calendar in the khal configuration and the vdir directory that holds its events. */
data class KhalCalendar(val name: String, val path: Path)

/**
 * An event to add to a khal calendar. Times are local wall-clock times in the processing.timezone
 * setting. A null [uid] is generated.
 */
data class CalendarEvent(
    val uid: String?,
    val title: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    val alarms: List<Duration>
)
