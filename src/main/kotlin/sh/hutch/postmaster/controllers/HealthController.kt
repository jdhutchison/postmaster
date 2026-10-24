package sh.hutch.postmaster.controllers

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.CacheControl
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import sh.hutch.postmaster.models.HealthReport
import sh.hutch.postmaster.models.HealthStatus
import sh.hutch.postmaster.services.HealthService

/**
 * The status endpoint, at `/health` and `/status` (CLARIFICATIONS.md R7): JSON saying whether the
 * application and its dependencies are up, which build it is, how long it has been up and what
 * processing is doing. HTTP 200 for OK and DEGRADED, 503 for DOWN, so `curl -f` works as a Docker
 * health check.
 */
@Path("/health")
@Tag(name = "Status")
class HealthController(private val health: HealthService) {
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "The application's status, its dependencies, version, uptime and processing")
  @APIResponse(
      responseCode = "200", description = "OK or DEGRADED",
      content = [Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class))]
  )
  @APIResponse(
      responseCode = "503", description = "DOWN: the database or Dovecot is unavailable",
      content = [Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class))]
  )
  fun health(): Response = respond(health.report())

  companion object {
    fun respond(report: HealthReport): Response =
      Response.status(if (report.status == HealthStatus.DOWN) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK)
          .entity(report)
          .type(MediaType.APPLICATION_JSON_TYPE)
          .cacheControl(CacheControl().apply { isNoStore = true })
          .build()
  }
}

/** `/status`, the same as `/health`. */
@Path("/status")
@Tag(name = "Status")
class StatusController(private val health: HealthService) {
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "The same as /health")
  @APIResponse(
      responseCode = "200", description = "OK or DEGRADED",
      content = [Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class))]
  )
  @APIResponse(
      responseCode = "503", description = "DOWN: the database or Dovecot is unavailable",
      content = [Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class))]
  )
  fun status(): Response = HealthController.respond(health.report())
}
