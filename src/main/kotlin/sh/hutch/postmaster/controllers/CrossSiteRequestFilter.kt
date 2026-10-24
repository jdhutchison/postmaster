package sh.hutch.postmaster.controllers

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerRequestFilter

/**
 * Refuses changes requested by other sites (cross-site request forgery). The pages sit behind
 * nginx basic auth, which the browser sends with any request to them, so without this a page
 * elsewhere could submit a form that, say, adds a rule forwarding all email. Browsers say where a
 * request came from in `Sec-Fetch-Site`; only requests from these pages (`same-origin`) or typed
 * by the user (`none`) may change anything. Clients that don't send it, such as curl, are allowed.
 */
class CrossSiteRequestFilter {
  @ServerRequestFilter(preMatching = true)
  fun refuseCrossSite(context: ContainerRequestContext): Response? {
    if (context.method.uppercase() in SAFE_METHODS) {
      return null
    }
    val site = context.getHeaderString(SEC_FETCH_SITE)?.trim()?.lowercase() ?: return null
    if (site == "same-origin" || site == "none") {
      return null
    }
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.TEXT_PLAIN_TYPE)
        .entity("Changes can only be made from Postmaster's own pages")
        .build()
  }

  companion object {
    const val SEC_FETCH_SITE = "Sec-Fetch-Site"
    private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
  }
}
