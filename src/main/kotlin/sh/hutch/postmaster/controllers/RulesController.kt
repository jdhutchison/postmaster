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
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestForm
import sh.hutch.postmaster.actions.ActionRegistry
import sh.hutch.postmaster.models.ActionInput
import sh.hutch.postmaster.models.ActionRow
import sh.hutch.postmaster.models.ConditionInput
import sh.hutch.postmaster.models.ConditionRow
import sh.hutch.postmaster.models.Option
import sh.hutch.postmaster.models.RuleForm
import sh.hutch.postmaster.models.RuleInput
import sh.hutch.postmaster.models.RuleSummary
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.services.CalendarService
import sh.hutch.postmaster.services.RuleService
import sh.hutch.postmaster.services.TypeService
import sh.hutch.postmaster.services.ValidationException

/**
 * The rule pages: lists of classification and action rules, and the create and edit pages
 * (REQUIREMENTS.md "Rules"). `{type}` is `classification` or `action`.
 */
@Path("/rules/{type: classification|action}")
@Tag(name = "Rules", description = "Classification and action rules")
class RulesController(
    private val rules: RuleService,
    private val types: TypeService,
    private val calendars: CalendarService,
    private val pageToasts: PageToasts,
    private val registry: ActionRegistry
) {

  @CheckedTemplate
  object Templates {
    @JvmStatic
    external fun rules(ruleType: String, title: String, pageKey: String, rules: List<RuleSummary>): TemplateInstance

    @JvmStatic
    external fun rulesTable(ruleType: String, rules: List<RuleSummary>): TemplateInstance

    @JvmStatic
    external fun ruleForm(form: RuleForm): TemplateInstance
  }

  /**
   * The fields of the rule form, for the OpenAPI description. Condition and action rows are sent as
   * `conditions[i].field` and `actions[i].field`, where `i` is any integer that's distinct per row.
   */
  @Schema(name = "RuleFormFields")
  class RuleFormFields {
    @Schema(description = "Rule name; required on create, ignored on update")
    var name: String? = null

    @Schema(description = "Required description")
    var description: String? = null

    @Schema(description = "Whole number of 0 or more; blank means 0")
    var priority: String? = null

    @Schema(description = "true to enable the rule (edit only)")
    var enabled: String? = null

    @Schema(name = "conditions[i].id", description = "Id of an existing condition")
    var conditionId: String? = null

    @Schema(name = "conditions[i].attribute", description = "subject, sender, message, events, attachments, bill_url, type or link")
    var conditionAttribute: String? = null

    @Schema(name = "conditions[i].matchType", description = "is, contains, starts_with, regex, has or is_present")
    var conditionMatchType: String? = null

    @Schema(name = "conditions[i].test", description = "Value to match; not used for has and is_present")
    var conditionTest: String? = null

    @Schema(name = "actions[i].id", description = "Id of an existing action")
    var actionId: String? = null

    @Schema(name = "actions[i].actionType", description = "One of the action types, e.g. move or set_type")
    var actionType: String? = null

    @Schema(name = "actions[i].destination", description = "Folder for move, address for forward_email")
    var actionDestination: String? = null

    @Schema(name = "actions[i].typeId", description = "Type id for set_type")
    var actionTypeId: String? = null

    @Schema(name = "actions[i].calendarId", description = "Calendar id for create_events (optional) and process_bill")
    var actionCalendarId: String? = null

    @Schema(name = "actions[i].notifications", description = "Notification durations for create_events, e.g. 4h, 1d")
    var actionNotifications: String? = null

    @Schema(name = "actions[i].enabled", description = "true when the action is enabled")
    var actionEnabled: String? = null
  }

  @GET
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Rules page for a rule type")
  fun page(@PathParam("type") type: String): TemplateInstance {
    val ruleType = RuleType.fromCode(type)
    return Templates.rules(type, title(ruleType), "rules-$type", rules.summaries(ruleType))
  }

  @GET
  @Path("/new")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Create rule page")
  fun newRule(@PathParam("type") type: String): TemplateInstance {
    val input = RuleInput("", "", "0", listOf(ConditionInput(0, null, "subject", "contains", "")), listOf(blankAction()))
    return Templates.ruleForm(form(RuleType.fromCode(type), null, input, emptyMap()))
  }

  @GET
  @Path("/{id: \\d+}/edit")
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Edit rule page")
  @APIResponse(responseCode = "404", description = "No such rule")
  fun editRule(@PathParam("type") type: String, @PathParam("id") id: Long): TemplateInstance {
    val ruleType = RuleType.fromCode(type)
    return Templates.ruleForm(form(ruleType, id, rules.input(ruleType, id), emptyMap()))
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Create a rule")
  @RequestBody(content = [Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED, schema = Schema(implementation = RuleFormFields::class))])
  @APIResponse(responseCode = "303", description = "Created; redirects to the rules page")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  fun create(@PathParam("type") type: String, @Parameter(hidden = true) fields: MultivaluedMap<String, String>): Response =
    save(RuleType.fromCode(type), null, parse(fields))

  @POST
  @Path("/{id: \\d+}")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Update a rule")
  @RequestBody(content = [Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED, schema = Schema(implementation = RuleFormFields::class))])
  @APIResponse(responseCode = "303", description = "Saved; redirects to the rules page")
  @APIResponse(responseCode = "422", description = "The form with validation errors")
  @APIResponse(responseCode = "404", description = "No such rule")
  fun update(
      @PathParam("type") type: String,
      @PathParam("id") id: Long,
      @Parameter(hidden = true) fields: MultivaluedMap<String, String>
  ): Response = save(RuleType.fromCode(type), id, parse(fields).copy(enabled = fields.getFirst("enabled") == "true"))

  @DELETE
  @Path("/{id: \\d+}")
  @Produces(MediaType.TEXT_HTML)
  @Operation(
      summary = "Delete a rule (HTMX)",
      description = "Returns the rules table, or with from=edit an HX-Redirect to the rules page"
  )
  @APIResponse(responseCode = "200", description = "The rules table, or an HX-Redirect")
  @APIResponse(responseCode = "404", description = "No such rule")
  fun delete(@PathParam("type") type: String, @PathParam("id") id: Long, @QueryParam("from") from: String?): Response {
    val ruleType = RuleType.fromCode(type)
    val rule = rules.delete(ruleType, id)
    val toast = Toast.success("Deleted rule ${rule.name}")
    if (from == "edit") {
      return Htmx.redirect("/rules/$type", toast)
    }
    return Htmx.response(Templates.rulesTable(type, rules.summaries(ruleType)), toasts = listOf(toast))
  }

  @POST
  @Path("/{id: \\d+}/enabled")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_HTML)
  @Operation(summary = "Enable or disable a rule (HTMX); enabled=true enables, anything else disables")
  @APIResponse(responseCode = "200", description = "The rules table")
  @APIResponse(responseCode = "404", description = "No such rule")
  fun setEnabled(@PathParam("type") type: String, @PathParam("id") id: Long, @RestForm("enabled") enabled: String?): Response {
    val ruleType = RuleType.fromCode(type)
    val rule = rules.setEnabled(ruleType, id, enabled == "true")
    val verb = if (rule.enabled) "Enabled" else "Disabled"
    return Htmx.response(Templates.rulesTable(type, rules.summaries(ruleType)), toasts = listOf(Toast.success("$verb rule ${rule.name}")))
  }

  private fun save(ruleType: RuleType, id: Long?, input: RuleInput): Response {
    if (id != null) {
      // 404 for an unknown rule rather than a validation error
      rules.get(ruleType, id)
    }
    return try {
      val rule = if (id == null) rules.create(ruleType, input) else rules.update(ruleType, id, input)
      Flash.redirect("/rules/${ruleType.code}", Toast.success((if (id == null) "Created rule " else "Saved rule ") + rule.name))
    } catch (e: ValidationException) {
      pageToasts.error(e.message ?: "Please correct the errors")
      val shown = if (id == null) input else input.copy(name = rules.get(ruleType, id).name)
      Response.status(422).entity(Templates.ruleForm(form(ruleType, id, shown, e.errors))).build()
    }
  }

  private fun form(ruleType: RuleType, id: Long?, input: RuleInput, errors: Map<String, String>): RuleForm =
    RuleForm(
        ruleType = ruleType,
        id = id,
        name = input.name.orEmpty(),
        description = input.description.orEmpty(),
        priority = input.priority.orEmpty(),
        enabled = input.enabled ?: true,
        conditions = input.conditions.map {
          ConditionRow(it.index.toString(), it.id?.toString().orEmpty(), it.attribute.orEmpty(), it.matchType.orEmpty(), it.test.orEmpty(), errors)
        },
        actions = input.actions.map {
          ActionRow(
              it.index.toString(), it.id?.toString().orEmpty(), it.actionType.orEmpty(), it.destination.orEmpty(),
              it.typeId.orEmpty(), it.calendarId.orEmpty(), it.notifications.orEmpty(), it.enabled, errors
          )
        },
        types = types.list().map { Option(it.id.toString(), it.name) },
        calendars = calendars.list().map { Option(it.id.toString(), it.name) },
        errors = errors,
        actionTypes = registry.options()
    )

  private fun blankAction() = ActionInput(0, null, "move", "", "", "", "", true)

  private fun title(ruleType: RuleType) = if (ruleType == RuleType.CLASSIFICATION) "Classification rules" else "Action rules"

  companion object {
    private val ROW_FIELD = Regex("""(conditions|actions)\[(-?\d+)]\.(\w+)""")

    /** Reads the rule form. Condition and action rows are ordered by their form index. */
    fun parse(fields: MultivaluedMap<String, String>): RuleInput {
      val rows = sortedMapOf<Int, MutableMap<String, String>>()
      val actionRows = sortedMapOf<Int, MutableMap<String, String>>()
      for ((name, values) in fields) {
        val match = ROW_FIELD.matchEntire(name) ?: continue
        val index = match.groupValues[2].toIntOrNull() ?: continue
        val target = if (match.groupValues[1] == "conditions") rows else actionRows
        target.getOrPut(index) { mutableMapOf() }[match.groupValues[3]] = values.firstOrNull().orEmpty()
      }
      return RuleInput(
          name = fields.getFirst("name"),
          description = fields.getFirst("description"),
          priority = fields.getFirst("priority"),
          conditions = rows.map { (index, row) ->
            ConditionInput(index, row["id"]?.toLongOrNull(), row["attribute"], row["matchType"], row["test"])
          },
          actions = actionRows.map { (index, row) ->
            ActionInput(
                index, row["id"]?.toLongOrNull(), row["actionType"], row["destination"], row["typeId"],
                row["calendarId"], row["notifications"], row["enabled"] == "true"
            )
          }
      )
    }
  }
}
