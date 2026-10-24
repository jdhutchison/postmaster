package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import sh.hutch.postmaster.models.Dashboard
import sh.hutch.postmaster.services.DashboardService
import sh.hutch.postmaster.services.ProcessingService

/** The dashboard: the latest processing runs, when the next is due, email counts and "Run now" (PLAN.md 6.3). */
@Path("/")
@Tag(name = "Pages")
class HomeController(private val dashboards: DashboardService, private val processing: ProcessingService) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun home(dashboard: Dashboard): TemplateInstance

    @JvmStatic
    external fun dashboard(dashboard: Dashboard): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Dashboard page")
  fun home(): TemplateInstance = Templates.home(dashboards.dashboard())

  @GET
  @Path("/dashboard")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "The dashboard's contents (HTMX fragment); it polls itself while a run is in progress")
  fun dashboard(): TemplateInstance = Templates.dashboard(dashboards.dashboard())

  @POST
  @Path("/runs")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Start processing now, in the background (HTMX)")
  @APIResponse(responseCode = "200", description = "The dashboard's contents, showing the run in progress")
  @APIResponse(responseCode = "409", description = "A run is already in progress")
  fun runNow(): Response {
    if (!processing.runNowAsync()) {
      return Htmx.response(null, status = 409, toasts = listOf(Toast.error("Processing is already running")))
    }
    return Htmx.response(Templates.dashboard(dashboards.dashboard()), toasts = listOf(Toast.success("Processing started")))
  }
}
