package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.models.TypeForm
import sh.hutch.postmaster.models.TypeInput
import sh.hutch.postmaster.services.TypeService
import sh.hutch.postmaster.services.ValidationException

/** The Types page: the types email can be classified as (REQUIREMENTS.md "Types"). */
@Path("/types")
@Tag(name = "Types", description = "Types email is classified as")
class TypesController(private val types: TypeService) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun types(types: List<TypeEntity>): TemplateInstance

    @JvmStatic
    external fun typesTable(types: List<TypeEntity>): TemplateInstance

    @JvmStatic
    external fun typeForm(form: TypeForm): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Types page")
  fun page(): TemplateInstance = Templates.types(types.list())

  @GET
  @Path("/new")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for a new type (HTMX fragment that opens the type dialog)")
  fun newForm(): Response = Htmx.dialogForm(Templates.typeForm(TypeForm.empty()), DIALOG)

  @GET
  @Path("/{id: \\d+}/edit")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Form for editing a type (HTMX fragment that opens the type dialog)")
  @APIResponse(responseCode = "404", description = "No such type")
  fun editForm(@PathParam("id") id: Long): Response {
    val type = types.get(id)
    return Htmx.dialogForm(
        Templates.typeForm(TypeForm(type.id, type.name, type.description.orEmpty(), type.colour)), DIALOG
    )
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Create a type (HTMX)")
  @APIResponse(responseCode = "200", description = "The types table, swapped into the page; the dialog closes")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  fun create(
      @RestForm("name") name: String?,
      @RestForm("description") description: String?,
      @RestForm("colour") colour: String?
  ): Response = save(null, TypeInput(name, description, colour))

  @PUT
  @Path("/{id: \\d+}")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Update a type (HTMX)")
  @APIResponse(responseCode = "200", description = "The types table, swapped into the page; the dialog closes")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  @APIResponse(responseCode = "404", description = "No such type")
  fun update(
      @PathParam("id") id: Long,
      @RestForm("name") name: String?,
      @RestForm("description") description: String?,
      @RestForm("colour") colour: String?
  ): Response = save(id, TypeInput(name, description, colour))

  @DELETE
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Delete a type (HTMX); system types can't be deleted")
  @APIResponse(responseCode = "200", description = "The types table")
  @APIResponse(responseCode = "409", description = "A system type")
  @APIResponse(responseCode = "404", description = "No such type")
  fun delete(@PathParam("id") id: Long): Response {
    val type = types.delete(id)
    return Htmx.response(Templates.typesTable(types.list()), toasts = listOf(Toast.success("Deleted type ${type.name}")))
  }

  private fun save(id: Long?, input: TypeInput): Response =
    try {
      val type = if (id == null) types.create(input) else types.update(id, input)
      Htmx.response(
          Templates.typesTable(types.list()),
          toasts = listOf(Toast.success((if (id == null) "Created type " else "Saved type ") + type.name)),
          events = Htmx.closeDialog(DIALOG),
          retarget = "#types-table-container",
          reswap = "innerHTML"
      )
    } catch (e: ValidationException) {
      val form = TypeForm(id, input.name.orEmpty(), input.description.orEmpty(), input.colour.orEmpty(), e.errors)
      Htmx.response(Templates.typeForm(form), status = 422, toasts = listOf(Toast.error(e.message ?: "Please correct the errors")))
    }

  companion object {
    const val DIALOG = "type-dialog"
  }
}
