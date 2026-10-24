package sh.hutch.postmaster.actions

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.entities.RuleActionEntity
import sh.hutch.postmaster.models.ActionTypes
import sh.hutch.postmaster.services.TodoListService

/**
 * Writes the email's open action items to the markdown to-do list (CLARIFICATIONS.md R5). Items
 * already there aren't written again. It runs after the LLM has found the items.
 */
@ApplicationScoped
class UpdateTodoListHandler(private val todos: TodoListService) : ActionHandler {
  override val actionType = ActionTypes.UPDATE_TODO_LIST
  override val stage = ActionStage.GATHER
  override val order = 60

  override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
    val email = context.email
    val open = email.openActionItems()
    val pending = open.filter { it.todoName == null }
    if (pending.isEmpty()) {
      return ActionOutcome.skipped(if (open.isEmpty()) "No action items" else "Its action items are already in the to-do list")
    }
    val names = pending.map { todos.add(email, it) }
    return ActionOutcome.done("Added to the to-do list: " + names.joinToString(", "))
  }
}
