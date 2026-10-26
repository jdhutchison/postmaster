package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.RestQuery
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.models.ActionItemView
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.AttachmentView
import sh.hutch.postmaster.models.EmailDetail
import sh.hutch.postmaster.models.EmailFilter
import sh.hutch.postmaster.models.EmailPage
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.EventView
import sh.hutch.postmaster.models.ManualResult
import sh.hutch.postmaster.services.EmailService
import sh.hutch.postmaster.services.MailboxException
import sh.hutch.postmaster.services.TypeService
import sh.hutch.postmaster.services.ValidationException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** The emails list and email detail pages (REQUIREMENTS.md "Viewing emails"). */
@Path("/emails")
@Tag(name = "Emails", description = "Processed emails and actions on them")
class EmailsController(private val emails: EmailService, private val types: TypeService) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun emails(result: EmailPage, types: List<TypeEntity>, statuses: List<EmailStatus>): TemplateInstance

    @JvmStatic
    external fun email(detail: EmailDetail, types: List<TypeEntity>): TemplateInstance

    @JvmStatic
    external fun moveForm(detail: EmailDetail, folders: List<String>, scannedAt: Instant?): TemplateInstance

    @JvmStatic
    external fun actionItemEventForm(detail: EmailDetail, item: ActionItemView): TemplateInstance

    @JvmStatic
    external fun eventCalendarForm(detail: EmailDetail, event: EventView): TemplateInstance

    @JvmStatic
    external fun attachmentPreview(emailId: Long, attachment: AttachmentView, kind: String): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(
      summary = "Emails page",
      description = "Emails received in the last 7 days, or every email with period=all, matching any filters, newest " +
          "first in pages of 50. Dates (yyyy-MM-dd, inclusive) replace the 7 days. Type is a type id or 'none'; values that " +
          "don't parse are ignored. Emails that are deleted, moved or done are left out unless untracked=true."
  )
  fun list(
      @RestQuery("from") from: String?,
      @RestQuery("to") to: String?,
      @RestQuery("status") status: String?,
      @RestQuery("type") type: String?,
      @RestQuery("sender") sender: String?,
      @RestQuery("response") response: String?,
      @RestQuery("action") action: String?,
      @RestQuery("errors") errors: String?,
      @RestQuery("untracked") untracked: String?,
      @RestQuery("period") period: String?,
      @RestQuery("page") page: String?
  ): TemplateInstance {
    val fromDate = date(from)
    val toDate = date(to)
    // The last few days unless dates are given or every email is asked for (CLARIFICATIONS.md R10)
    val showAll = period == EmailFilter.PERIOD_ALL && fromDate == null && toDate == null
    val filter = EmailFilter(
        from = fromDate,
        to = toDate,
        status = status?.let { code -> EmailStatus.entries.firstOrNull { it.code == code } },
        typeId = when (type) {
          null, "" -> null
          EmailFilter.TYPE_NONE -> EmailFilter.UNCLASSIFIED
          else -> type.toLongOrNull()?.takeIf { it > 0 }
        },
        sender = sender?.trim()?.ifEmpty { null },
        responseRequired = response == "true",
        actionRequired = action == "true",
        withErrors = errors == "true",
        includeUntracked = untracked == "true",
        lastDays = if (fromDate == null && toDate == null && !showAll) EmailFilter.DEFAULT_DAYS else null,
        showAll = showAll,
        page = page?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    )
    return Templates.emails(emails.search(filter), types.list(), EmailStatus.entries)
  }

  @GET
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Email detail page")
  @APIResponse(responseCode = "404", description = "No such email")
  fun detail(@PathParam("id") id: Long): TemplateInstance = Templates.email(emails.detail(id), types.list())

  @DELETE
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Delete an email (HTMX): moves it to the trash folder; the email is no longer tracked but can still be viewed")
  @APIResponse(responseCode = "200", description = "HX-Redirect to the emails page")
  @APIResponse(responseCode = "502", description = "The mailbox refused; an error toast says why")
  @APIResponse(responseCode = "404", description = "No such email")
  @APIResponse(responseCode = "409", description = "The email isn't tracked, so it can't be acted on")
  fun delete(@PathParam("id") id: Long): Response {
    val result = emails.delete(id, ActionSource.UI)
    return if (result.ok) Htmx.redirect("/emails", Toast.success("Deleted the email: ${result.message}")) else failed(result)
  }

  @POST
  @Path("/{id: \\d+}/read")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Mark an email read (read=true) or unread (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "502", description = "The mailbox refused; an error toast says why")
  fun setRead(@PathParam("id") id: Long, @RestForm("read") read: String?): Response =
    done(id, emails.setRead(id, read == "true", ActionSource.UI))

  @GET
  @Path("/{id: \\d+}/move")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for moving an email to another folder (HTMX fragment that opens the move dialog)")
  fun moveForm(@PathParam("id") id: Long): Response {
    val detail = emails.detail(id)
    return try {
      val folders = emails.folders()
      Htmx.dialogForm(Templates.moveForm(detail, folders.filter { it != detail.folder }, emails.foldersScannedAt()), MOVE_DIALOG)
    } catch (e: MailboxException) {
      Htmx.response(null, status = 502, toasts = listOf(Toast.error(e.message ?: "The mailbox can't be reached")))
    }
  }

  @POST
  @Path("/{id: \\d+}/move/folders")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Scan the mailbox's folders again (HTMX): the move form, with the folders found")
  @APIResponse(responseCode = "502", description = "The mailbox can't be reached; an error toast says why")
  fun refreshFolders(@PathParam("id") id: Long): Response {
    val detail = emails.detail(id)
    return try {
      val folders = emails.refreshFolders()
      Htmx.response(
          Templates.moveForm(detail, folders.filter { it != detail.folder }, emails.foldersScannedAt()),
          toasts = listOf(Toast.success("Found ${folders.size} folder${if (folders.size == 1) "" else "s"}"))
      )
    } catch (e: MailboxException) {
      Htmx.response(null, status = 502, toasts = listOf(Toast.error(e.message ?: "The mailbox can't be reached")))
    }
  }

  @POST
  @Path("/{id: \\d+}/move")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Move an email to another IMAP folder (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "422", description = "No folder chosen")
  @APIResponse(responseCode = "502", description = "The mailbox refused; an error toast says why")
  fun move(@PathParam("id") id: Long, @RestForm("folder") folder: String?): Response =
    try {
      done(id, emails.move(id, folder, ActionSource.UI))
    } catch (e: ValidationException) {
      invalid(e)
    }

  @POST
  @Path("/{id: \\d+}/type")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Set an email's type (typeId), or clear it when typeId is blank (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "422", description = "No such type")
  fun setType(@PathParam("id") id: Long, @RestForm("typeId") typeId: String?): Response =
    try {
      done(id, emails.setType(id, typeId?.toLongOrNull(), ActionSource.UI))
    } catch (e: ValidationException) {
      invalid(e)
    }

  @POST
  @Path("/{id: \\d+}/response-required")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Set (required=true) or clear an email's response required flag (HTMX)")
  fun setResponseRequired(@PathParam("id") id: Long, @RestForm("required") required: String?): Response =
    done(id, emails.setResponseRequired(id, required == "true", ActionSource.UI))

  @POST
  @Path("/{id: \\d+}/action-required")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Set (required=true) or clear an email's action required flag (HTMX); bills can't need action")
  @APIResponse(responseCode = "502", description = "Refused, e.g. for a bill; an error toast says why")
  fun setActionRequired(@PathParam("id") id: Long, @RestForm("required") required: String?): Response =
    done(id, emails.setActionRequired(id, required == "true", null, ActionSource.UI))

  @POST
  @Path("/{id: \\d+}/action-items/{itemId: \\d+}/done")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Mark an action item done, and tick it off in the to-do list if it's there (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "404", description = "No such email or action item")
  @APIResponse(responseCode = "502", description = "The to-do list couldn't be updated; an error toast says why")
  fun completeActionItem(@PathParam("id") id: Long, @PathParam("itemId") itemId: Long): Response =
    done(id, emails.completeActionItem(id, itemId, ActionSource.UI))

  @POST
  @Path("/{id: \\d+}/done")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Mark an email done (HTMX): its action items are done, no reply is needed, and it's no longer tracked")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "409", description = "The email isn't tracked")
  @APIResponse(responseCode = "502", description = "The to-do list couldn't be updated; an error toast says why")
  fun markDone(@PathParam("id") id: Long): Response = done(id, emails.markDone(id, ActionSource.UI))

  @POST
  @Path("/{id: \\d+}/reopen")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Reopen an email marked done (HTMX): it's tracked again")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "409", description = "The email isn't done")
  fun reopen(@PathParam("id") id: Long): Response = done(id, emails.reopen(id, ActionSource.UI))

  @POST
  @Path("/{id: \\d+}/events/{eventId: \\d+}/action-item")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Make an event that isn't in a calendar an action item (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "404", description = "No such email or event")
  @APIResponse(responseCode = "409", description = "The event is in a calendar, the email is a bill, or it isn't tracked")
  @APIResponse(responseCode = "502", description = "The to-do list couldn't be written; an error toast says why")
  fun eventToActionItem(@PathParam("id") id: Long, @PathParam("eventId") eventId: Long): Response =
    done(id, emails.eventToActionItem(id, eventId, ActionSource.UI))

  @GET
  @Path("/{id: \\d+}/action-items/{itemId: \\d+}/event")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for making an action item an event (HTMX fragment that opens the dialog)")
  @APIResponse(responseCode = "404", description = "No such email or action item")
  fun actionItemEventForm(@PathParam("id") id: Long, @PathParam("itemId") itemId: Long): Response {
    val detail = emails.detail(id)
    val item = detail.actionItem(itemId) ?: throw NotFoundException("Action item $itemId not found")
    return Htmx.dialogForm(Templates.actionItemEventForm(detail, item), ITEM_EVENT_DIALOG)
  }

  @POST
  @Path("/{id: \\d+}/action-items/{itemId: \\d+}/event")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(
      summary = "Make an action item an event (HTMX)",
      description = "date (yyyy-MM-dd) is required; time (HH:mm) is optional, all day without one; with calendarId the event " +
          "is added to that calendar. An item in the to-do list is removed from it."
  )
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "422", description = "No date, or no such enabled calendar")
  @APIResponse(responseCode = "409", description = "The item is done, or the email isn't tracked")
  @APIResponse(responseCode = "502", description = "The to-do list or khal failed; an error toast says why")
  fun actionItemToEvent(
      @PathParam("id") id: Long,
      @PathParam("itemId") itemId: Long,
      @RestForm("date") date: String?,
      @RestForm("time") time: String?,
      @RestForm("calendarId") calendarId: String?
  ): Response =
    try {
      val day = date(date)
      val at = try {
        time?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) }
      } catch (e: DateTimeParseException) {
        throw ValidationException(mapOf("time" to "The time must be like 09:30"))
      }
      // A calendar id that isn't a number is no calendar, and is refused
      val calendar = calendarId?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: -1L }
      done(id, emails.actionItemToEvent(id, itemId, day, at, calendar, ActionSource.UI))
    } catch (e: ValidationException) {
      invalid(e)
    }

  @POST
  @Path("/{id: \\d+}/reprocess")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Process an email again from its message in the mailbox (HTMX)")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "502", description = "Reprocessing failed; an error toast says why")
  fun reprocess(@PathParam("id") id: Long): Response = done(id, emails.reprocess(id))

  @GET
  @Path("/{id: \\d+}/events/{eventId: \\d+}/calendar")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Calendar choice for an event (HTMX fragment that opens the event calendar dialog)")
  @APIResponse(responseCode = "404", description = "No such email or event")
  fun eventCalendarForm(@PathParam("id") id: Long, @PathParam("eventId") eventId: Long): Response {
    val detail = emails.detail(id)
    val event = detail.event(eventId) ?: throw NotFoundException("Event $eventId not found")
    return Htmx.dialogForm(Templates.eventCalendarForm(detail, event), EVENT_DIALOG)
  }

  @POST
  @Path("/{id: \\d+}/events/{eventId: \\d+}/calendar")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Put an event in another calendar (HTMX): moved in khal if added, otherwise added")
  @APIResponse(responseCode = "200", description = "HX-Redirect back to the email")
  @APIResponse(responseCode = "422", description = "No such enabled calendar")
  @APIResponse(responseCode = "502", description = "khal failed; an error toast says why")
  fun changeEventCalendar(
      @PathParam("id") id: Long,
      @PathParam("eventId") eventId: Long,
      @RestForm("calendarId") calendarId: String?
  ): Response =
    try {
      done(id, emails.changeEventCalendar(id, eventId, calendarId?.toLongOrNull(), ActionSource.UI))
    } catch (e: ValidationException) {
      invalid(e)
    }

  @GET
  @Path("/{id: \\d+}/attachments/{attachmentId: \\d+}")
  @Operation(summary = "Download an attachment; inline=true shows it in the browser when it can be previewed")
  @APIResponse(responseCode = "404", description = "No such attachment, or it hasn't been downloaded")
  fun attachment(
      @PathParam("id") id: Long,
      @PathParam("attachmentId") attachmentId: Long,
      @RestQuery("inline") inline: String?
  ): Response {
    val file = emails.attachmentFile(id, attachmentId)
    val type = file.mediaType
    // Only types that can't run script are shown inline
    val disposition = if (inline == "true" && file.attachment.preview != null) "inline" else "attachment"
    return Response.ok(file.path.toFile())
        .type(if (type.startsWith("text/")) "$type; charset=UTF-8" else type)
        .header("Content-Disposition", disposition(disposition, file.attachment.filename))
        .header("X-Content-Type-Options", "nosniff")
        .build()
  }

  @GET
  @Path("/{id: \\d+}/attachments/{attachmentId: \\d+}/preview")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Preview of an attachment (HTMX fragment that opens the preview dialog)")
  @APIResponse(responseCode = "404", description = "No such attachment, or it can't be previewed")
  fun attachmentPreview(@PathParam("id") id: Long, @PathParam("attachmentId") attachmentId: Long): Response {
    val attachment = emails.attachmentFile(id, attachmentId).attachment
    val kind = attachment.preview ?: throw NotFoundException("${attachment.filename} can't be previewed")
    return Htmx.dialogForm(Templates.attachmentPreview(id, attachment, kind), PREVIEW_DIALOG)
  }

  // ---------------------------------------------------------------------------------------------

  private fun done(id: Long, result: ManualResult): Response =
    if (result.ok) Htmx.redirect("/emails/$id", Toast.success(result.message)) else failed(result)

  private fun failed(result: ManualResult): Response =
    Htmx.response(null, status = 502, toasts = listOf(Toast.error(result.message)))

  private fun invalid(e: ValidationException): Response =
    Htmx.response(null, status = 422, toasts = listOf(Toast.error(e.message ?: "Please correct the errors")))

  private fun date(text: String?): LocalDate? =
    try {
      text?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    } catch (e: DateTimeParseException) {
      null
    }

  companion object {
    const val MOVE_DIALOG = "move-dialog"
    const val EVENT_DIALOG = "event-calendar-dialog"
    const val PREVIEW_DIALOG = "attachment-dialog"
    const val ITEM_EVENT_DIALOG = "action-item-event-dialog"

    private fun disposition(type: String, filename: String): String {
      val ascii = filename.replace(Regex("[^\\x20-\\x7e]|[\"\\\\]"), "_")
      val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
      return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
  }
}
