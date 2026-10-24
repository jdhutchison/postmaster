package sh.hutch.postmaster.models

import io.quarkus.qute.TemplateData

// What the admin page forms show: field values (as typed, after a failed save) and errors by
// field name. @TemplateData lets the untyped rule row tags use them in native images.

/** An option in a select. */
@TemplateData
data class Option(val value: String, val label: String)

@TemplateData
data class TypeForm(
    val id: Long?,
    val name: String,
    val description: String,
    val colour: String,
    val errors: Map<String, String> = emptyMap()
) {
  fun error(field: String): String? = errors[field]

  fun invalid(field: String): Boolean = errors.containsKey(field)

  companion object {
    const val DEFAULT_COLOUR = "#6272a4"

    fun empty() = TypeForm(null, "", "", DEFAULT_COLOUR)
  }
}

@TemplateData
data class CalendarForm(
    val id: Long?,
    val name: String,
    val description: String,
    val colour: String,
    val enabled: Boolean,
    val notificationPeriods: String,
    val khal: KhalCalendars,
    val errors: Map<String, String> = emptyMap()
) {
  /** The khal calendars, plus the current name if khal no longer lists it. */
  val nameOptions: List<String>
    get() = if (name.isNotEmpty() && name !in khal.names) listOf(name) + khal.names else khal.names

  fun error(field: String): String? = errors[field]

  fun invalid(field: String): Boolean = errors.containsKey(field)
}

@TemplateData
data class PromptForm(
    val id: Long?,
    val key: String?,
    val name: String,
    val contents: String,
    val errors: Map<String, String> = emptyMap()
) {
  val placeholders: List<Placeholder>
    get() = Placeholders.ALL

  fun error(field: String): String? = errors[field]

  fun invalid(field: String): Boolean = errors.containsKey(field)
}

/** A match type option; [attributes] lists, space separated, the attributes it applies to (Q9). */
@TemplateData
data class MatchOption(val value: String, val label: String, val attributes: String, val needsTest: Boolean)

@TemplateData
data class ConditionRow(
    val index: String,
    val id: String,
    val attribute: String,
    val matchType: String,
    val test: String,
    val errors: Map<String, String> = emptyMap()
) {
  fun error(field: String): String? = errors["conditions[$index].$field"]

  fun invalid(field: String): Boolean = errors.containsKey("conditions[$index].$field")
}

@TemplateData
data class ActionRow(
    val index: String,
    val id: String,
    val actionType: String,
    val destination: String,
    val typeId: String,
    val calendarId: String,
    val notifications: String,
    val enabled: Boolean,
    val errors: Map<String, String> = emptyMap()
) {
  fun error(field: String): String? = errors["actions[$index].$field"]

  fun invalid(field: String): Boolean = errors.containsKey("actions[$index].$field")
}

@TemplateData
data class RuleForm(
    val ruleType: RuleType,
    val id: Long?,
    val name: String,
    val description: String,
    val priority: String,
    val enabled: Boolean,
    val conditions: List<ConditionRow>,
    val actions: List<ActionRow>,
    val types: List<Option>,
    val calendars: List<Option>,
    val errors: Map<String, String> = emptyMap(),
    /** Every registered action type (ActionRegistry.options()) */
    val actionTypes: List<Option> = RuleOptions.ACTION_TYPES
) {
  val ruleTypeCode: String
    get() = ruleType.code

  val title: String
    get() = (if (id == null) "New " else "Edit ") + ruleType.code + " rule"

  /** The layout's menu key for this rule type's pages. */
  val pageKey: String
    get() = "rules-" + ruleType.code

  val attributes: List<Option>
    get() = RuleOptions.ATTRIBUTES

  val matchTypes: List<MatchOption>
    get() = RuleOptions.MATCH_TYPES

  /** Empty rows for the "add" buttons' templates; the index is replaced in the browser. */
  val conditionTemplate: ConditionRow
    get() = ConditionRow(TEMPLATE_INDEX, "", ConditionAttribute.SUBJECT.code, MatchType.CONTAINS.code, "")

  val actionTemplate: ActionRow
    get() = ActionRow(TEMPLATE_INDEX, "", ActionTypes.MOVE, "", "", "", "", true)

  /** Whether a condition or action row can be removed: at least one of each must remain. */
  val canRemoveCondition: Boolean
    get() = conditions.size > 1

  val canRemoveAction: Boolean
    get() = actions.size > 1

  fun error(field: String): String? = errors[field]

  fun invalid(field: String): Boolean = errors.containsKey(field)

  companion object {
    const val TEMPLATE_INDEX = "__i__"
  }
}

/** Labels for the options in the rule form. */
object RuleOptions {
  val ATTRIBUTES: List<Option> = listOf(
      ConditionAttribute.SUBJECT to "Subject",
      ConditionAttribute.SENDER to "Sender",
      ConditionAttribute.MESSAGE to "Message",
      ConditionAttribute.EVENTS to "Events",
      ConditionAttribute.ATTACHMENTS to "Attachments",
      ConditionAttribute.BILL_URL to "Bill URL",
      ConditionAttribute.TYPE to "Type",
      ConditionAttribute.LINK to "Link"
  ).map { Option(it.first.code, it.second) }

  val MATCH_TYPES: List<MatchOption> = listOf(
      MatchType.IS to "is",
      MatchType.CONTAINS to "contains",
      MatchType.STARTS_WITH to "starts with",
      MatchType.REGEX to "matches regex",
      MatchType.HAS to "has",
      MatchType.IS_PRESENT to "is present"
  ).map { (type, label) ->
    MatchOption(
        type.code, label,
        ConditionAttribute.entries.filter { type.appliesTo(it) }.joinToString(" ") { it.code },
        type.needsTest
    )
  }

  /** Labels of the built-in action types; other types use their handler's label. */
  val ACTION_LABELS = mapOf(
      ActionTypes.SET_TYPE to "Set type",
      ActionTypes.MOVE to "Move",
      ActionTypes.CHECK_FOR_EVENTS to "Check for events",
      ActionTypes.DOWNLOAD_ATTACHMENTS to "Download attachments",
      ActionTypes.SET_REPLY_NEEDED to "Set reply needed",
      ActionTypes.FETCH_LINK_CONTENTS to "Fetch link contents",
      ActionTypes.CREATE_EVENTS to "Create events",
      ActionTypes.SKIP_PROCESSING to "Skip processing",
      ActionTypes.MARK_READ to "Mark read",
      ActionTypes.MARK_UNREAD to "Mark unread",
      ActionTypes.PROCESS_BILL to "Process bill",
      ActionTypes.FORWARD_EMAIL to "Forward email",
      ActionTypes.DELETE to "Delete"
  )

  val ACTION_TYPES: List<Option> = ActionTypes.BUILT_IN.map { Option(it, ACTION_LABELS[it] ?: it) }
}
