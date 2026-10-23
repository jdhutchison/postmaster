package sh.hutch.postmaster.models

import io.quarkus.qute.TemplateData

// Inputs to the services from the admin forms. Fields hold the raw text from the form so the
// services can validate them and report every problem by form field name.

data class TypeInput(val name: String?, val description: String?, val colour: String?)

data class CalendarInput(
    val name: String?,
    val description: String?,
    val colour: String?,
    val enabled: Boolean,
    val notificationPeriods: String?
)

data class PromptInput(val name: String?, val contents: String?)

/** A rule from the create or edit form. [enabled] is null on create (rules start enabled). */
data class RuleInput(
    val name: String?,
    val description: String?,
    val priority: String?,
    val conditions: List<ConditionInput>,
    val actions: List<ActionInput>,
    val enabled: Boolean? = null
)

/** A condition row; [index] is the row's index in the form, used in error keys. */
data class ConditionInput(
    val index: Int,
    val id: Long?,
    val attribute: String?,
    val matchType: String?,
    val test: String?
)

/** An action row; [index] is the row's index in the form, used in error keys. */
data class ActionInput(
    val index: Int,
    val id: Long?,
    val actionType: String?,
    val destination: String?,
    val typeId: String?,
    val calendarId: String?,
    val notifications: String?,
    val enabled: Boolean
)

/** The calendars in the khal configuration, or why they couldn't be read. */
@TemplateData
data class KhalCalendars(val names: List<String>, val error: String?)

/** A rule as shown in the rule lists. */
data class RuleSummary(
    val id: Long,
    val name: String,
    val description: String,
    val priority: Int,
    val enabled: Boolean,
    val conditionCount: Int,
    val actionCount: Int
)
