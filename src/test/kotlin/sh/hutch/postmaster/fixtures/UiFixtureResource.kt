package sh.hutch.postmaster.fixtures

import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateData
import io.quarkus.qute.TemplateInstance
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import sh.hutch.postmaster.controllers.Flash
import sh.hutch.postmaster.controllers.PageToasts
import sh.hutch.postmaster.controllers.Toast
import sh.hutch.postmaster.controllers.Toasts
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A test-only page built from the builder's layout and UI components (PLAN
 * 2.3), so the components can be tested before the real pages exist. Served at
 * /mail/test-fixtures/ui in tests only.
 */
@Path("/test-fixtures/ui")
class UiFixtureResource {
  @Inject
  @Location("fixtures/ui.html")
  lateinit var page: Template

  @Inject
  lateinit var pageToasts: PageToasts

  @Inject
  lateinit var actions: FixtureActions

  /**
   * The fixture page.
   *
   * @param toast raise a toast of this level (`success`, `info`, `error`) on the page load
   * @param rows how many table rows to render (default 60, enough to scroll)
   */
  @GET
  @Produces(MediaType.TEXT_HTML)
  fun get(
      @QueryParam("toast") toast: String?,
      @QueryParam("message") message: String?,
      @QueryParam("rows") rows: Int?,
      @QueryParam("followed") followed: String?
  ): TemplateInstance {
    val text = message ?: "Fixture ${toast.orEmpty()}"
    when (toast) {
      "success" -> pageToasts.success(text)
      "info" -> pageToasts.info(text)
      "error" -> pageToasts.error(text)
    }
    val now = Instant.now()
    return page.data("rows", FixtureRow.rows(rows ?: 60, now))
        .data("times", FixtureTime.times(now))
        .data("followed", followed.orEmpty())
  }

  /** The HTMX action behind a confirm: counts the call and raises a success toast */
  @POST
  @Path("action")
  @Produces(MediaType.TEXT_HTML)
  fun action(): Response {
    val count = actions.increment()
    return Response.ok("Done $count")
        .header(Toasts.HEADER, Toasts.hxTrigger(Toast.success("Action done")))
        .build()
  }

  /** An HTMX response that only raises a toast */
  @POST
  @Path("toast")
  @Produces(MediaType.TEXT_HTML)
  fun toast(@QueryParam("level") level: String, @QueryParam("message") message: String): Response {
    val toast = when (level) {
      "error" -> Toast.error(message)
      "info" -> Toast.info(message)
      else -> Toast.success(message)
    }
    return Response.ok("").header(Toasts.HEADER, Toasts.hxTrigger(toast)).build()
  }

  /** An HTMX request that fails without raising a toast of its own */
  @POST
  @Path("fail")
  @Produces(MediaType.TEXT_HTML)
  fun fail(): Response = Response.serverError().entity("boom").build()

  /** A plain form post that redirects back with a flash toast */
  @POST
  @Path("form-action")
  fun formAction(): Response {
    actions.increment()
    return Flash.redirect("/mail/test-fixtures/ui", Toast.success("Form submitted"))
  }

  /** Content loaded into a dialog by HTMX, which then opens the dialog */
  @GET
  @Path("dialog-body")
  @Produces(MediaType.TEXT_HTML)
  fun dialogBody(): Response =
      Response.ok("<p data-testid=\"loaded-content\">Loaded by HTMX</p>")
          .header("HX-Trigger-After-Swap", """{"pm-dialog-open":{"id":"loaded-dialog"}}""")
          .build()
}

/** Counts the fixture's actions, so tests can tell whether a confirmed action ran */
@ApplicationScoped
class FixtureActions {
  private val count = AtomicInteger()

  fun increment(): Int = count.incrementAndGet()

  fun count(): Int = count.get()
}

@TemplateData
data class FixtureRow(
    val name: String,
    val sender: String,
    val subject: String,
    val received: Instant,
    val notes: String,
    val title: String?
) {
  companion object {
    const val LONG_SUBJECT = "A very long subject that cannot possibly fit in its column, so it is truncated with an " +
        "ellipsis and shows the whole text in a tooltip when the pointer rests on it"

    fun rows(count: Int, now: Instant): List<FixtureRow> = (1..count).map { n ->
      FixtureRow(
          name = "Row $n",
          sender = "sender$n@example.test",
          subject = if (n == 1) LONG_SUBJECT else "Subject $n",
          received = now.minus(Duration.ofMinutes(n * 37L)),
          notes = "Notes for row $n",
          title = if (n == 2) "Explicit tooltip for row 2" else null
      )
    }
  }
}

@TemplateData
data class FixtureTime(val key: String, val value: Instant) {
  companion object {
    fun times(now: Instant): List<FixtureTime> = listOf(
        FixtureTime("seconds", now.minusSeconds(30)),
        FixtureTime("minutes", now.minus(Duration.ofMinutes(14))),
        FixtureTime("hours", now.minus(Duration.ofHours(5))),
        FixtureTime("days", now.minus(Duration.ofDays(2))),
        FixtureTime("many-days", now.minus(Duration.ofDays(45)))
    )
  }
}
