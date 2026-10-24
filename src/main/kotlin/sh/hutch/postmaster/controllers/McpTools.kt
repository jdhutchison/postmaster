package sh.hutch.postmaster.controllers

import io.quarkiverse.mcp.server.Tool
import io.quarkiverse.mcp.server.ToolArg
import io.quarkiverse.mcp.server.ToolCallException
import jakarta.ws.rs.NotFoundException
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.CalendarOption
import sh.hutch.postmaster.models.EmailDetail
import sh.hutch.postmaster.models.EmailFilter
import sh.hutch.postmaster.models.EmailRow
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ManualResult
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.services.EmailService
import sh.hutch.postmaster.services.MailboxException
import sh.hutch.postmaster.services.TypeService
import sh.hutch.postmaster.services.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The MCP server's tools, for Hermes and its postmaster profile (REQUIREMENTS.md "MCP server",
 * CLARIFICATIONS.md D13). Served over Streamable HTTP at `/mail/mcp`. Actions are recorded against
 * the email with source `mcp`; failures come back as tool errors.
 */
class McpTools(
    private val emails: EmailService,
    private val types: TypeService,
    private val calendars: CalendarRepository
) {

  /** A page of search results. */
  data class SearchResult(val total: Long, val page: Int, val pageCount: Int, val emails: List<EmailRow>)

  /** A type emails can be classified as. */
  data class TypeInfo(val id: Long, val name: String, val description: String?)

  // Lists are wrapped so each tool answers with one JSON document

  data class Types(val types: List<TypeInfo>)

  data class Folders(val folders: List<String>)

  data class Calendars(val calendars: List<CalendarOption>)

  @Tool(
      name = "search_emails",
      description = "Searches processed emails, newest first. With no filters, returns the 30 most recently received; " +
          "with any filter, matching emails in pages of 50. Each result has the email's id, subject, sender, type, " +
          "flags and counts of events, attachments and errors; use get_email for everything else."
  )
  fun searchEmails(
      @ToolArg(description = "Received on or after this date, yyyy-MM-dd", required = false) from: String?,
      @ToolArg(description = "Received on or before this date, yyyy-MM-dd", required = false) to: String?,
      @ToolArg(description = "Processing status: pending, processed, skipped or failed", required = false) status: String?,
      @ToolArg(description = "Type name (see list_types), or 'none' for unclassified emails", required = false) type: String?,
      @ToolArg(description = "Text in the sender's name or address", required = false) sender: String?,
      @ToolArg(name = "response_required", description = "Only emails that need a response", required = false) responseRequired: Boolean?,
      @ToolArg(name = "action_required", description = "Only emails that need action", required = false) actionRequired: Boolean?,
      @ToolArg(name = "with_errors", description = "Only emails with processing errors", required = false) withErrors: Boolean?,
      @ToolArg(description = "Page number, from 1", required = false) page: Int?
  ): SearchResult {
    val filter = EmailFilter(
        from = date("from", from),
        to = date("to", to),
        status = status?.takeIf { it.isNotBlank() }?.let { code ->
          EmailStatus.entries.firstOrNull { it.code == code.trim().lowercase() }
              ?: throw ToolCallException("Unknown status '$code'; use pending, processed, skipped or failed")
        },
        typeId = type?.takeIf { it.isNotBlank() }?.let { typeId(it) },
        sender = sender?.trim()?.ifEmpty { null },
        responseRequired = responseRequired == true,
        actionRequired = actionRequired == true,
        withErrors = withErrors == true,
        page = page?.takeIf { it > 0 } ?: 1
    )
    val result = emails.search(filter)
    return SearchResult(result.total, result.page, result.pageCount, result.rows)
  }

  @Tool(
      name = "get_email",
      description = "Everything about one processed email: its details, summary, message text, linked page, bill details, " +
          "events, attachments, the rules that matched and the actions they ran, actions done by hand, and errors."
  )
  fun getEmail(@ToolArg(description = "The email's id") id: Long): EmailDetail = found { emails.detail(id) }

  @Tool(
      name = "set_email_type",
      description = "Sets an email's type, or clears it when type is empty. Setting the Bill type clears action required."
  )
  fun setEmailType(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "Type name (see list_types); empty to clear the type", required = false) type: String?
  ): String {
    val typeId = type?.takeIf { it.isNotBlank() }?.let { typeId(it) }
    return done { emails.setType(id, typeId, ActionSource.MCP) }
  }

  @Tool(name = "move_email", description = "Moves an email to another mailbox folder (see list_folders).")
  fun moveEmail(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "The folder's full name, e.g. Folders/Bills") folder: String
  ): String = done { emails.move(id, folder, ActionSource.MCP) }

  @Tool(name = "mark_email_read", description = "Marks an email read, or unread when read is false.")
  fun markEmailRead(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "true for read, false for unread", required = false, defaultValue = "true") read: Boolean
  ): String = done { emails.setRead(id, read, ActionSource.MCP) }

  @Tool(
      name = "delete_email",
      description = "Deletes an email: the message moves to the trash folder and the email no longer appears in searches."
  )
  fun deleteEmail(@ToolArg(description = "The email's id") id: Long): String = done { emails.delete(id, ActionSource.MCP) }

  @Tool(name = "forward_email", description = "Forwards an email, with its attachments, to an address.")
  fun forwardEmail(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "The address to forward to") to: String
  ): String = done { emails.forward(id, to, ActionSource.MCP) }

  @Tool(name = "set_response_required", description = "Sets or clears whether an email needs a response.")
  fun setResponseRequired(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "true if a response is needed, false once it's been dealt with") required: Boolean
  ): String = done { emails.setResponseRequired(id, required, ActionSource.MCP) }

  @Tool(
      name = "set_action_required",
      description = "Sets or clears whether an email needs action (other than a reply). Bills never need action."
  )
  fun setActionRequired(
      @ToolArg(description = "The email's id") id: Long,
      @ToolArg(description = "true if action is needed, false once it's done") required: Boolean,
      @ToolArg(description = "What needs doing, when setting it", required = false) description: String?
  ): String = done { emails.setActionRequired(id, required, description, ActionSource.MCP) }

  @Tool(
      name = "set_event_calendar",
      description = "Puts one of an email's events in a calendar (see list_calendars). An event already in a calendar " +
          "is moved; one that isn't is added."
  )
  fun setEventCalendar(
      @ToolArg(name = "email_id", description = "The email's id") emailId: Long,
      @ToolArg(name = "event_id", description = "The event's id, from get_email") eventId: Long,
      @ToolArg(description = "The calendar's name") calendar: String
  ): String {
    val target = calendars.listEnabled().firstOrNull { it.name.equals(calendar.trim(), ignoreCase = true) }
        ?: throw ToolCallException("There's no enabled calendar named '$calendar'")
    return done { emails.changeEventCalendar(emailId, eventId, target.id, ActionSource.MCP) }
  }

  @Tool(
      name = "reprocess_email",
      description = "Processes an email again from the mailbox: rules, the LLM and actions run again, replacing its " +
          "previous results. Events already added to calendars are kept."
  )
  fun reprocessEmail(@ToolArg(description = "The email's id") id: Long): String = done { emails.reprocess(id) }

  @Tool(name = "list_types", description = "The types emails can be classified as.")
  fun listTypes(): Types = Types(types.list().map { TypeInfo(it.id!!, it.name, it.description) })

  @Tool(name = "list_folders", description = "The mailbox folders emails can be moved to.")
  fun listFolders(): Folders =
    try {
      Folders(emails.folders())
    } catch (e: MailboxException) {
      throw ToolCallException(e.message ?: "The mailbox can't be reached")
    }

  @Tool(name = "list_calendars", description = "The enabled calendars events can be put in.")
  fun listCalendars(): Calendars = Calendars(calendars.listEnabled().map { CalendarOption(it.id!!, it.name, it.colour) })

  // ---------------------------------------------------------------------------------------------

  private fun typeId(name: String): Long =
    if (name.trim().equals(EmailFilter.TYPE_NONE, ignoreCase = true)) {
      EmailFilter.UNCLASSIFIED
    } else {
      types.list().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
          ?: throw ToolCallException("There's no type named '$name'; see list_types")
    }

  private fun date(arg: String, text: String?): LocalDate? =
    try {
      text?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    } catch (e: DateTimeParseException) {
      throw ToolCallException("$arg must be a date like 2026-10-24")
    }

  private fun <T> found(block: () -> T): T =
    try {
      block()
    } catch (e: NotFoundException) {
      throw ToolCallException(e.message ?: "Not found")
    }

  private fun done(block: () -> ManualResult): String {
    val result = try {
      found(block)
    } catch (e: ValidationException) {
      throw ToolCallException(e.message ?: "Invalid arguments")
    }
    if (!result.ok) {
      throw ToolCallException(result.message)
    }
    return result.message
  }
}
