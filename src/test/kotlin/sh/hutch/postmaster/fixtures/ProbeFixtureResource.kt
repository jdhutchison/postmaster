package sh.hutch.postmaster.fixtures

import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Checks that test-only resources and templates are served in tests */
@Path("/test-fixtures/probe")
class ProbeFixtureResource {
  @Inject
  @Location("fixtures/probe.html")
  lateinit var probe: Template

  @GET
  @Produces(MediaType.TEXT_HTML)
  fun get(): TemplateInstance = probe.data("message", "fixture served")
}
