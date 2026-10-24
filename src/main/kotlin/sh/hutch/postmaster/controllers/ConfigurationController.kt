package sh.hutch.postmaster.controllers

import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import sh.hutch.postmaster.models.Setting
import sh.hutch.postmaster.models.SettingGroup
import sh.hutch.postmaster.models.SettingType
import sh.hutch.postmaster.services.OperationNotAllowedException
import sh.hutch.postmaster.services.SettingValidationException
import sh.hutch.postmaster.services.SettingsService

/** The Configuration page: every setting, edited in place (REQUIREMENTS.md "Configuration"). */
@Path("/configuration")
@Tag(name = "Configuration", description = "Settings stored in the database")
class ConfigurationController(private val settings: SettingsService) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun configuration(groups: List<SettingGroup>): TemplateInstance

    @JvmStatic
    external fun value(setting: Setting): TemplateInstance

    @JvmStatic
    external fun edit(setting: Setting, value: String, error: String?): TemplateInstance
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Configuration page")
  fun page(): TemplateInstance = Templates.configuration(SettingGroup.of(settings.list()))

  @GET
  @Path("/{key}/edit")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Field for editing a setting (HTMX fragment)")
  @APIResponse(responseCode = "200", description = "The edit field")
  @APIResponse(responseCode = "404", description = "No such setting")
  fun edit(@PathParam("key") key: String): TemplateInstance {
    val setting = settings.get(key)
    return Templates.edit(setting, if (setting.type == SettingType.ENCRYPTED) "" else setting.value.orEmpty(), null)
  }

  @PUT
  @Path("/{key}")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(
      summary = "Update a setting (HTMX fragment)",
      description = "Validates the value for the setting's type. Encrypted settings are encrypted before " +
          "they are stored; a blank value leaves them unchanged."
  )
  @APIResponse(responseCode = "200", description = "The setting's displayed value", content = [Content(mediaType = MediaType.TEXT_HTML)])
  @APIResponse(responseCode = "422", description = "The edit field with the validation error", content = [Content(mediaType = MediaType.TEXT_HTML)])
  @APIResponse(responseCode = "404", description = "No such setting")
  fun update(@PathParam("key") key: String, @RestForm("value") value: String?): Response {
    val before = settings.get(key)
    val text = value.orEmpty()
    return try {
      val after = settings.update(key, text)
      val changed = if (before.type == SettingType.ENCRYPTED) text.isNotBlank() else after.value != before.value
      val toasts = if (changed) listOf(Toast.success("Saved ${before.label}")) else emptyList()
      Htmx.response(Templates.value(after), toasts = toasts)
    } catch (e: SettingValidationException) {
      val message = e.message ?: "The value isn't valid"
      Htmx.response(Templates.edit(before, text, message), status = 422, toasts = listOf(Toast.error(message)))
    }
  }

  @DELETE
  @Path("/{key}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Clear an encrypted setting (HTMX fragment)")
  @APIResponse(responseCode = "200", description = "The setting's displayed value")
  @APIResponse(responseCode = "409", description = "The setting isn't encrypted")
  fun clear(@PathParam("key") key: String): Response {
    val setting = settings.get(key)
    if (setting.type != SettingType.ENCRYPTED) {
      throw OperationNotAllowedException("Only encrypted settings can be cleared")
    }
    val after = settings.clear(key)
    return Htmx.response(Templates.value(after), toasts = listOf(Toast.success("Cleared ${setting.label}")))
  }
}
