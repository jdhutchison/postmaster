package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import sh.hutch.postmaster.actions.ActionRegistry
import sh.hutch.postmaster.entities.CalendarEntity
import sh.hutch.postmaster.entities.ConditionEntity
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.entities.RuleEntity
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.models.ActionInput
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.models.ConditionAttribute
import sh.hutch.postmaster.models.ConditionInput
import sh.hutch.postmaster.models.ConditionJoin
import sh.hutch.postmaster.models.MatchType
import sh.hutch.postmaster.models.RuleInput
import sh.hutch.postmaster.models.RuleSummary
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.repositories.CalendarRepository
import sh.hutch.postmaster.repositories.RuleRepository
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.util.Reminders
import java.util.regex.PatternSyntaxException

/**
 * Manages classification and action rules: their conditions (all or any of which must match, Q21, R8) and the
 * actions they trigger.
 */
@ApplicationScoped
@Transactional
class RuleService(
    private val rules: RuleRepository,
    private val types: TypeRepository,
    private val calendars: CalendarRepository,
    private val registry: ActionRegistry
) {

  fun list(ruleType: RuleType): List<RuleEntity> = rules.listActive(ruleType)

  /** The active rules of a type, as shown in the rule lists. */
  fun summaries(ruleType: RuleType): List<RuleSummary> =
    rules.listActive(ruleType).map {
      RuleSummary(
          id = it.id!!,
          name = it.name,
          description = it.description,
          priority = it.priority,
          enabled = it.enabled,
          conditionCount = it.conditions.size,
          actionCount = it.actions.count { action -> !action.deleted },
          conditionJoin = it.conditionJoin.code
      )
    }

  fun get(ruleType: RuleType, id: Long): RuleEntity {
    val rule = rules.findActive(id)
    if (rule == null || rule.ruleType != ruleType) {
      throw NotFoundException("Rule $id not found")
    }
    return rule
  }

  /** The rule as form input, for the edit page. */
  fun input(ruleType: RuleType, id: Long): RuleInput {
    val rule = get(ruleType, id)
    return RuleInput(
        name = rule.name,
        description = rule.description,
        priority = rule.priority.toString(),
        conditions = rule.conditions.mapIndexed { index, condition ->
          ConditionInput(index, condition.id, condition.attribute.code, condition.matchType.code, condition.test)
        },
        actions = rule.actions.filter { !it.deleted }.mapIndexed { index, action ->
          ActionInput(
              index = index,
              id = action.id,
              actionType = action.actionType,
              destination = action.destination,
              typeId = action.type?.id?.toString(),
              calendarId = action.calendar?.id?.toString(),
              notifications = action.notifications,
              enabled = action.enabled
          )
        },
        enabled = rule.enabled,
        conditionJoin = rule.conditionJoin.code
    )
  }

  fun create(ruleType: RuleType, input: RuleInput): RuleEntity {
    val errors = Errors()
    val name = errors.required("name", input.name, "Name", MAX_NAME)
    if (name != null) {
      val other = rules.findActiveByName(ruleType, name)
      if (other != null) {
        errors.duplicate("name", "A ${ruleType.code} rule named '${other.name}' already exists")
      }
    }
    val validated = validate(input, errors, null)
    errors.throwIfAny()

    val rule = RuleEntity()
    rule.name = name!!
    rule.ruleType = ruleType
    rule.enabled = input.enabled ?: true
    applyRule(rule, validated)
    rules.persist(rule)
    return rule
  }

  /** Updates the rule. Its name can't be changed, so [RuleInput.name] is ignored. */
  fun update(ruleType: RuleType, id: Long, input: RuleInput): RuleEntity {
    val rule = get(ruleType, id)
    val errors = Errors()
    val validated = validate(input, errors, rule)
    errors.throwIfAny()
    input.enabled?.let { rule.enabled = it }
    applyRule(rule, validated)
    return rule
  }

  fun delete(ruleType: RuleType, id: Long): RuleEntity {
    val rule = get(ruleType, id)
    rule.markDeleted()
    return rule
  }

  fun setEnabled(ruleType: RuleType, id: Long, enabled: Boolean): RuleEntity {
    val rule = get(ruleType, id)
    rule.enabled = enabled
    return rule
  }

  private class ValidCondition(
      val id: Long?,
      val attribute: ConditionAttribute,
      val matchType: MatchType,
      val test: String?
  )

  private class ValidAction(
      val id: Long?,
      val actionType: String,
      val destination: String?,
      val type: TypeEntity?,
      val calendar: CalendarEntity?,
      val notifications: String?,
      val enabled: Boolean
  )

  private class ValidRule(
      val description: String,
      val priority: Int,
      val conditionJoin: ConditionJoin,
      val conditions: List<ValidCondition>,
      val actions: List<ValidAction>
  )

  private fun validate(input: RuleInput, errors: Errors, existing: RuleEntity?): ValidRule {
    val description = errors.required("description", input.description, "Description")
    val priorityText = input.priority?.trim().orEmpty()
    val priority = if (priorityText.isEmpty()) 0 else priorityText.toIntOrNull()?.takeIf { it >= 0 }
    if (priority == null) {
      errors.add("priority", "Priority must be a whole number of 0 or more")
    }
    val joinText = input.conditionJoin?.trim().orEmpty()
    val conditionJoin = if (joinText.isEmpty()) ConditionJoin.ALL else ConditionJoin.entries.firstOrNull { it.code == joinText }
    if (conditionJoin == null) {
      errors.add("conditionJoin", "Choose whether all or any of the conditions must match")
    }
    if (input.conditions.isEmpty()) {
      errors.add("conditions", "A rule needs at least one condition")
    }
    if (input.actions.isEmpty()) {
      errors.add("actions", "A rule needs at least one action")
    }
    val conditionIds = existing?.conditions?.mapNotNull { it.id }?.toSet() ?: emptySet()
    val actionIds = existing?.actions?.filter { !it.deleted }?.mapNotNull { it.id }?.toSet() ?: emptySet()
    val conditions = input.conditions.mapNotNull { validateCondition(it, errors, conditionIds) }
    val actions = input.actions.mapNotNull { validateAction(it, errors, actionIds) }
    return ValidRule(description.orEmpty(), priority ?: 0, conditionJoin ?: ConditionJoin.ALL, conditions, actions)
  }

  private fun validateCondition(input: ConditionInput, errors: Errors, existingIds: Set<Long>): ValidCondition? {
    val key = "conditions[${input.index}]"
    if (input.id != null && input.id !in existingIds) {
      errors.add("$key.id", "This condition no longer exists")
      return null
    }
    val attribute = ConditionAttribute.entries.firstOrNull { it.code == input.attribute }
    if (attribute == null) {
      errors.add("$key.attribute", "Choose an attribute")
    }
    val matchType = MatchType.entries.firstOrNull { it.code == input.matchType }
    if (matchType == null) {
      errors.add("$key.matchType", "Choose a match type")
    }
    if (attribute == null || matchType == null) {
      return null
    }
    if (!matchType.appliesTo(attribute)) {
      errors.add("$key.matchType", "'${matchType.code}' can't be used with ${attribute.code}")
      return null
    }
    if (!matchType.needsTest) {
      return ValidCondition(input.id, attribute, matchType, null)
    }
    val test = input.test?.trim().orEmpty()
    when {
      test.isEmpty() -> errors.add("$key.test", "A value to match is required")
      test.length > MAX_TEST -> errors.add("$key.test", "The value to match must be at most $MAX_TEST characters")
      matchType == MatchType.REGEX && !compiles(test) -> errors.add("$key.test", "'$test' is not a valid regular expression")
      else -> return ValidCondition(input.id, attribute, matchType, test)
    }
    return null
  }

  private fun validateAction(input: ActionInput, errors: Errors, existingIds: Set<Long>): ValidAction? {
    val key = "actions[${input.index}]"
    if (input.id != null && input.id !in existingIds) {
      errors.add("$key.id", "This action no longer exists")
      return null
    }
    val actionType = input.actionType?.trim()
    if (actionType.isNullOrEmpty() || actionType !in registry.types()) {
      errors.add("$key.actionType", "Choose an action")
      return null
    }
    var destination: String? = null
    var type: TypeEntity? = null
    var calendar: CalendarEntity? = null
    var notifications: String? = null
    var valid = true
    fun invalid(field: String, message: String) {
      errors.add("$key.$field", message)
      valid = false
    }
    when (actionType) {
      ActionTypes.MOVE -> {
        destination = input.destination?.trim()?.ifEmpty { null }
        when {
          destination == null -> invalid("destination", "The folder to move to is required")
          destination.length > MAX_DESTINATION -> invalid("destination", "The folder must be at most $MAX_DESTINATION characters")
        }
      }
      ActionTypes.FORWARD_EMAIL -> {
        destination = input.destination?.trim()?.ifEmpty { null }
        when {
          destination == null -> invalid("destination", "The address to forward to is required")
          !EMAIL.matches(destination) -> invalid("destination", "'$destination' is not an email address")
        }
      }
      ActionTypes.SET_TYPE -> {
        type = input.typeId?.trim()?.toLongOrNull()?.let { types.findActive(it) }
        if (type == null) {
          invalid("typeId", "Choose a type")
        }
      }
      ActionTypes.PROCESS_BILL -> {
        calendar = input.calendarId?.trim()?.toLongOrNull()?.let { calendars.findActive(it) }
        if (calendar == null) {
          invalid("calendarId", "Choose the calendar for bill reminders")
        }
      }
      ActionTypes.CREATE_EVENTS -> {
        val calendarId = input.calendarId?.trim()?.ifEmpty { null }
        if (calendarId != null) {
          calendar = calendarId.toLongOrNull()?.let { calendars.findActive(it) }
          if (calendar == null) {
            invalid("calendarId", "Choose a calendar")
          }
        }
        notifications = input.notifications?.trim()?.ifEmpty { null }
        if (notifications != null && !Reminders.isValidList(notifications)) {
          invalid(
              "notifications",
              "Notifications must be ISO 8601 durations or times like d-1@20:00, separated by a comma and a space, such as 4h, d-1@20:00"
          )
        }
      }
    }
    return if (valid) ValidAction(input.id, actionType, destination, type, calendar, notifications, input.enabled) else null
  }

  private fun applyRule(rule: RuleEntity, valid: ValidRule) {
    rule.description = valid.description
    rule.priority = valid.priority
    rule.conditionJoin = valid.conditionJoin

    val existingConditions = rule.conditions.associateBy { it.id }
    val keptConditions = valid.conditions.mapIndexed { position, item ->
      val condition = item.id?.let { existingConditions[it] } ?: ConditionEntity().also { it.rule = rule }
      condition.attribute = item.attribute
      condition.matchType = item.matchType
      condition.test = item.test
      condition.position = position
      condition
    }
    rule.conditions.retainAll(keptConditions.toSet())
    keptConditions.filter { it !in rule.conditions }.forEach { rule.conditions.add(it) }

    val existingActions = rule.actions.filter { !it.deleted }.associateBy { it.id }
    val keptActions = valid.actions.mapIndexed { position, item ->
      val action = item.id?.let { existingActions[it] } ?: RuleActionEntity().also {
        it.rule = rule
        rule.actions.add(it)
      }
      action.actionType = item.actionType
      action.destination = item.destination
      action.type = item.type
      action.calendar = item.calendar
      action.notifications = item.notifications
      action.enabled = item.enabled
      action.position = position
      action
    }.toSet()
    existingActions.values.filter { it !in keptActions }.forEach { it.markDeleted() }
  }

  private fun compiles(pattern: String): Boolean =
    try {
      Regex(pattern)
      true
    } catch (e: PatternSyntaxException) {
      false
    }

  companion object {
    const val MAX_NAME = 100
    const val MAX_TEST = 1000
    const val MAX_DESTINATION = 500
    private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
  }
}
