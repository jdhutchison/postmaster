package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/** The home page. A placeholder until the dashboard (PLAN.md 6.3) replaces it. */
@Path("/")
@Tag(name = "Pages")
class HomeController {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun home(): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Home page")
  fun home(): TemplateInstance = Templates.home()
}
