package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.models.CalendarForm
import sh.hutch.postmaster.models.CalendarInput
import sh.hutch.postmaster.models.TypeForm
import sh.hutch.postmaster.services.CalendarService
import sh.hutch.postmaster.services.ValidationException

/** The Calendars page: the khal calendars events can be added to (REQUIREMENTS.md "Calendars"). */
@Path("/calendars")
@Tag(name = "Calendars", description = "Calendars events are added to")
class CalendarsController(private val calendars: CalendarService) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun calendars(calendars: List<CalendarEntity>): TemplateInstance

    @JvmStatic
    external fun calendarsTable(calendars: List<CalendarEntity>): TemplateInstance

    @JvmStatic
    external fun calendarForm(form: CalendarForm): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Calendars page")
  fun page(): TemplateInstance = Templates.calendars(calendars.list())

  @GET
  @Path("/new")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for a new calendar (HTMX fragment that opens the calendar dialog)")
  fun newForm(): Response {
    val form = CalendarForm(null, "", "", TypeForm.DEFAULT_COLOUR, true, "", calendars.khalCalendars())
    return Htmx.dialogForm(Templates.calendarForm(form), DIALOG)
  }

  @GET
  @Path("/{id: \\d+}/edit")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for editing a calendar (HTMX fragment that opens the calendar dialog)")
  @APIResponse(responseCode = "404", description = "No such calendar")
  fun editForm(@PathParam("id") id: Long): Response {
    val calendar = calendars.get(id)
    val form = CalendarForm(
        calendar.id, calendar.name, calendar.description, calendar.colour, calendar.enabled,
        calendar.notificationPeriods.orEmpty(), calendars.khalCalendars()
    )
    return Htmx.dialogForm(Templates.calendarForm(form), DIALOG)
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Create a calendar (HTMX)")
  @APIResponse(responseCode = "200", description = "The calendars table, swapped into the page; the dialog closes")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  fun create(
      @RestForm("name") name: String?,
      @RestForm("description") description: String?,
      @RestForm("colour") colour: String?,
      @RestForm("enabled") enabled: String?,
      @RestForm("notificationPeriods") notificationPeriods: String?
  ): Response = save(null, CalendarInput(name, description, colour, enabled == "true", notificationPeriods))

  @PUT
  @Path("/{id: \\d+}")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Update a calendar (HTMX)")
  @APIResponse(responseCode = "200", description = "The calendars table, swapped into the page; the dialog closes")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  @APIResponse(responseCode = "404", description = "No such calendar")
  fun update(
      @PathParam("id") id: Long,
      @RestForm("name") name: String?,
      @RestForm("description") description: String?,
      @RestForm("colour") colour: String?,
      @RestForm("enabled") enabled: String?,
      @RestForm("notificationPeriods") notificationPeriods: String?
  ): Response = save(id, CalendarInput(name, description, colour, enabled == "true", notificationPeriods))

  @POST
  @Path("/{id: \\d+}/enabled")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Enable or disable a calendar (HTMX); enabled=true enables, anything else disables")
  @APIResponse(responseCode = "200", description = "The calendars table")
  @APIResponse(responseCode = "404", description = "No such calendar")
  fun setEnabled(@PathParam("id") id: Long, @RestForm("enabled") enabled: String?): Response {
    val calendar = calendars.setEnabled(id, enabled == "true")
    val verb = if (calendar.enabled) "Enabled" else "Disabled"
    return Htmx.response(Templates.calendarsTable(calendars.list()), toasts = listOf(Toast.success("$verb calendar ${calendar.name}")))
  }

  @DELETE
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Delete a calendar (HTMX)")
  @APIResponse(responseCode = "200", description = "The calendars table")
  @APIResponse(responseCode = "404", description = "No such calendar")
  fun delete(@PathParam("id") id: Long): Response {
    val calendar = calendars.delete(id)
    return Htmx.response(Templates.calendarsTable(calendars.list()), toasts = listOf(Toast.success("Deleted calendar ${calendar.name}")))
  }

  private fun save(id: Long?, input: CalendarInput): Response =
    try {
      val calendar = if (id == null) calendars.create(input) else calendars.update(id, input)
      Htmx.response(
          Templates.calendarsTable(calendars.list()),
          toasts = listOf(Toast.success((if (id == null) "Created calendar " else "Saved calendar ") + calendar.name)),
          events = Htmx.closeDialog(DIALOG),
          retarget = "#calendars-table-container",
          reswap = "innerHTML"
      )
    } catch (e: ValidationException) {
      val form = CalendarForm(
          id, input.name.orEmpty(), input.description.orEmpty(), input.colour.orEmpty(), input.enabled,
          input.notificationPeriods.orEmpty(), calendars.khalCalendars(), e.errors
      )
      Htmx.response(Templates.calendarForm(form), status = 422, toasts = listOf(Toast.error(e.message ?: "Please correct the errors")))
    }

  companion object {
    const val DIALOG = "calendar-dialog"
  }
}
