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
import java.time.LocalDate
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
    external fun moveForm(detail: EmailDetail, folders: List<String>): TemplateInstance

    @JvmStatic
    external fun eventCalendarForm(detail: EmailDetail, event: EventView): TemplateInstance

    @JvmStatic
    external fun attachmentPreview(emailId: Long, attachment: AttachmentView, kind: String): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(
      summary = "Emails page",
      description = "Without filters, the 30 most recently received emails. With any filter, matching emails in pages of 50. " +
          "Dates are yyyy-MM-dd (inclusive); type is a type id or 'none'; values that don't parse are ignored."
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
      @RestQuery("page") page: String?
  ): TemplateInstance {
    val filter = EmailFilter(
        from = date(from),
        to = date(to),
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
  @Operation(summary = "Delete an email (HTMX): moves it to the trash folder and soft-deletes the record")
  @APIResponse(responseCode = "200", description = "HX-Redirect to the emails page")
  @APIResponse(responseCode = "502", description = "The mailbox refused; an error toast says why")
  @APIResponse(responseCode = "404", description = "No such email")
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
      Htmx.dialogForm(Templates.moveForm(detail, emails.folders().filter { it != detail.folder }), MOVE_DIALOG)
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

    private fun disposition(type: String, filename: String): String {
      val ascii = filename.replace(Regex("[^\\x20-\\x7e]|[\"\\\\]"), "_")
      val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
      return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
  }
}
