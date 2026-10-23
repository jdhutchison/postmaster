package sh.hutch.postmaster.controllers

import com.fasterxml.jackson.core.JacksonException
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import java.util.Base64

/**
 * Toasts carried across a redirect in the `pm_flash` cookie, so they show on the page the browser
 * goes to next (post/redirect/get).
 */
object Flash {
  const val COOKIE = "pm_flash"
  private const val MAX_AGE_SECONDS = 60

  /**
   * A `303 See Other` to [location] that shows [toasts] on the next page. A [location] starting
   * with `/` that isn't already under the root path is taken as relative to it, so `/types`
   * becomes `/mail/types`.
   *
   * The `Location` header is the path alone. `Response.seeOther` would make it absolute from the
   * request, which behind the HTTPS proxy gives an `http://` URL (and it resolves paths against the
   * root path a second time).
   */
  fun redirect(location: String, vararg toasts: Toast): Response {
    val response = Response.status(Response.Status.SEE_OTHER).header(HttpHeaders.LOCATION, resolve(location))
    if (toasts.isNotEmpty()) {
      response.cookie(cookie(toasts.toList()))
    }
    return response.build()
  }

  fun cookie(toasts: List<Toast>): NewCookie =
    NewCookie.Builder(COOKIE)
        .value(encode(toasts))
        .path(cookiePath())
        .httpOnly(true)
        .sameSite(NewCookie.SameSite.LAX)
        .maxAge(MAX_AGE_SECONDS)
        .build()

  /** A cookie that removes the flash cookie from the browser. */
  fun clearingCookie(): NewCookie =
    NewCookie.Builder(COOKIE)
        .value("")
        .path(cookiePath())
        .httpOnly(true)
        .sameSite(NewCookie.SameSite.LAX)
        .maxAge(0)
        .build()

  fun encode(toasts: List<Toast>): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Toasts.mapper().writeValueAsBytes(toasts.map { Toasts.toJson(it) }))

  /** The toasts in a flash cookie value; empty if it's missing or not valid. */
  fun decode(value: String?): List<Toast> {
    if (value.isNullOrBlank()) {
      return emptyList()
    }
    return try {
      val items = Toasts.mapper().readValue(Base64.getUrlDecoder().decode(value), List::class.java)
      items.mapNotNull { Toasts.fromJson(it) }
    } catch (e: IllegalArgumentException) {
      emptyList()
    } catch (e: JacksonException) {
      emptyList()
    }
  }

  private fun cookiePath(): String = base().ifEmpty { "/" }

  /** [location] with the root path prefixed if it starts with `/` and isn't already under it. */
  fun resolve(location: String): String {
    val base = base()
    if (!location.startsWith("/") || base.isEmpty() || location == base || location.startsWith("$base/")) {
      return location
    }
    return base + location
  }
}

/**
 * Clears the flash cookie once a full page has been rendered with its toasts. HTMX requests return
 * fragments, which don't render the layout, so they leave it for the next full page.
 */
class FlashCookieFilter {
  @ServerResponseFilter
  fun clearConsumedFlash(request: ContainerRequestContext, response: ContainerResponseContext) {
    if (!request.cookies.containsKey(Flash.COOKIE) || request.getHeaderString("HX-Request") != null) {
      return
    }
    val html = response.mediaType?.isCompatible(MediaType.TEXT_HTML_TYPE) == true || response.entity is TemplateInstance
    if (html && response.status in 200..299) {
      response.headers.add(HttpHeaders.SET_COOKIE, Flash.clearingCookie())
    }
  }
}
