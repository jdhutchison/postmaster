package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import sh.hutch.postmaster.entities.PromptEntity
import sh.hutch.postmaster.models.PromptForm
import sh.hutch.postmaster.models.PromptInput
import sh.hutch.postmaster.services.PromptService
import sh.hutch.postmaster.services.ValidationException

/** The Prompts pages: the prompts used with the LLM and their editor (REQUIREMENTS.md "Prompts"). */
@Path("/prompts")
@Tag(name = "Prompts", description = "Prompts used with the LLM")
class PromptsController(private val prompts: PromptService, private val pageToasts: PageToasts) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun prompts(prompts: List<PromptEntity>): TemplateInstance

    @JvmStatic
    external fun promptsTable(prompts: List<PromptEntity>): TemplateInstance

    @JvmStatic
    external fun promptEditor(form: PromptForm): TemplateInstance

    @JvmStatic
    external fun preview(html: String): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Prompts page")
  fun page(): TemplateInstance = Templates.prompts(prompts.list())

  @GET
  @Path("/new")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Editor for a new prompt")
  fun newPrompt(): TemplateInstance = Templates.promptEditor(PromptForm(null, null, "", ""))

  @GET
  @Path("/{id: \\d+}/edit")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Editor for a prompt")
  @APIResponse(responseCode = "404", description = "No such prompt")
  fun editPrompt(@PathParam("id") id: Long): TemplateInstance {
    val prompt = prompts.get(id)
    return Templates.promptEditor(PromptForm(prompt.id, prompt.promptKey, prompt.name, prompt.contents))
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Create a prompt")
  @APIResponse(responseCode = "303", description = "Created; redirects to the prompts page")
  @APIResponse(responseCode = "422", description = "The editor with validation errors")
  fun create(@RestForm("name") name: String?, @RestForm("contents") contents: String?): Response =
    save(null, PromptInput(name, contents))

  @POST
  @Path("/{id: \\d+}")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Update a prompt")
  @APIResponse(responseCode = "303", description = "Saved; redirects to the prompts page")
  @APIResponse(responseCode = "422", description = "The editor with validation errors")
  @APIResponse(responseCode = "404", description = "No such prompt")
  fun update(@PathParam("id") id: Long, @RestForm("name") name: String?, @RestForm("contents") contents: String?): Response =
    save(id, PromptInput(name, contents))

  @DELETE
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Delete a prompt (HTMX); system prompts can't be deleted")
  @APIResponse(responseCode = "200", description = "The prompts table")
  @APIResponse(responseCode = "409", description = "A system prompt")
  @APIResponse(responseCode = "404", description = "No such prompt")
  fun delete(@PathParam("id") id: Long): Response {
    val prompt = prompts.delete(id)
    return Htmx.response(Templates.promptsTable(prompts.list()), toasts = listOf(Toast.success("Deleted prompt ${prompt.name}")))
  }

  @POST
  @Path("/preview")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Render prompt text as markdown (HTMX fragment); raw HTML is escaped")
  fun preview(@RestForm("contents") contents: String?): TemplateInstance = Templates.preview(prompts.preview(contents.orEmpty()))

  private fun save(id: Long?, input: PromptInput): Response {
    val key = id?.let { prompts.get(it).promptKey }
    return try {
      val prompt = if (id == null) prompts.create(input) else prompts.update(id, input)
      Flash.redirect("/prompts", Toast.success((if (id == null) "Created prompt " else "Saved prompt ") + prompt.name))
    } catch (e: ValidationException) {
      pageToasts.error(e.message ?: "Please correct the errors")
      val form = PromptForm(id, key, input.name.orEmpty(), input.contents.orEmpty(), e.errors)
      Response.status(422).entity(Templates.promptEditor(form)).build()
    }
  }
}
