package sh.hutch.postmaster.controllers

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.CacheControl
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import sh.hutch.postmaster.models.DraftHealthReport
import sh.hutch.postmaster.models.HealthReport
import sh.hutch.postmaster.models.HealthStatus
import sh.hutch.postmaster.services.HealthService

/**
 * The status endpoint, at `/health` and `/status` (CLARIFICATIONS.md R7): JSON saying whether the
 * application and its dependencies are up, which build it is, how long it has been up and what
 * processing is doing. HTTP 200 for OK and DEGRADED, 503 for DOWN, so `curl -f` works as a Docker
 * health check. A client that asks for `application/health+json` gets the IETF draft's format.
 */
@Path("/health")
@Tag(name = "Status")
class HealthController(private val health: HealthService) {
  @GET
  @Produces(MediaType.APPLICATION_JSON, HEALTH_JSON)
  @Operation(summary = "The application's status, its dependencies, version, uptime and processing")
  @APIResponse(
      responseCode = "200", description = "OK or DEGRADED (pass or warn)",
      content = [
        Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class)),
        Content(mediaType = HEALTH_JSON, schema = Schema(implementation = DraftHealthReport::class))
      ]
  )
  @APIResponse(
      responseCode = "503", description = "DOWN (fail): the database or Dovecot is unavailable",
      content = [
        Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class)),
        Content(mediaType = HEALTH_JSON, schema = Schema(implementation = DraftHealthReport::class))
      ]
  )
  fun health(@Context headers: HttpHeaders): Response = respond(health, headers)

  companion object {
    /** The IETF draft's media type. */
    const val HEALTH_JSON = "application/health+json"
    private val HEALTH_JSON_TYPE = MediaType.valueOf(HEALTH_JSON)

    fun respond(health: HealthService, headers: HttpHeaders): Response {
      val report = health.report()
      val response = Response.status(if (report.status == HealthStatus.DOWN) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK)
          .cacheControl(CacheControl().apply { isNoStore = true })
      return if (wantsDraft(headers)) {
        response.entity(health.draft(report)).type(HEALTH_JSON_TYPE).build()
      } else {
        response.entity(report).type(MediaType.APPLICATION_JSON_TYPE).build()
      }
    }

    /** Whether the most preferred of the two formats the client accepts is the draft's; a wildcard gets plain JSON. */
    private fun wantsDraft(headers: HttpHeaders): Boolean {
      val preferred = headers.acceptableMediaTypes.firstOrNull {
        it.isCompatible(HEALTH_JSON_TYPE) || it.isCompatible(MediaType.APPLICATION_JSON_TYPE)
      }
      return preferred != null && !preferred.isWildcardSubtype && preferred.isCompatible(HEALTH_JSON_TYPE)
    }
  }
}

/** `/status`, the same as `/health`. */
@Path("/status")
@Tag(name = "Status")
class StatusController(private val health: HealthService) {
  @GET
  @Produces(MediaType.APPLICATION_JSON, HealthController.HEALTH_JSON)
  @Operation(summary = "The same as /health")
  @APIResponse(
      responseCode = "200", description = "OK or DEGRADED (pass or warn)",
      content = [
        Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class)),
        Content(mediaType = HealthController.HEALTH_JSON, schema = Schema(implementation = DraftHealthReport::class))
      ]
  )
  @APIResponse(
      responseCode = "503", description = "DOWN (fail): the database or Dovecot is unavailable",
      content = [
        Content(mediaType = MediaType.APPLICATION_JSON, schema = Schema(implementation = HealthReport::class)),
        Content(mediaType = HealthController.HEALTH_JSON, schema = Schema(implementation = DraftHealthReport::class))
      ]
  )
  fun status(@Context headers: HttpHeaders): Response = HealthController.respond(health, headers)
}
