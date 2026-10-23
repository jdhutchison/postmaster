package sh.hutch.postmaster.controllers

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import sh.hutch.postmaster.services.OperationNotAllowedException
import sh.hutch.postmaster.services.UnknownSettingException

/** Maps service exceptions that reach the presentation layer to responses with an error toast. */
class ExceptionMappers {

  @ServerExceptionMapper
  fun notAllowed(e: OperationNotAllowedException): Response =
    Htmx.response(e.message, status = 409, toasts = listOf(Toast.error(e.message ?: "That isn't allowed")))
        .withType(MediaType.TEXT_PLAIN_TYPE)

  @ServerExceptionMapper
  fun unknownSetting(e: UnknownSettingException): Response =
    Htmx.response(e.message, status = 404, toasts = listOf(Toast.error(e.message ?: "Unknown setting")))
        .withType(MediaType.TEXT_PLAIN_TYPE)

  private fun Response.withType(type: MediaType): Response = Response.fromResponse(this).type(type).build()
}
