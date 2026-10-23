package sh.hutch.postmaster.services

/** Input that isn't valid. [errors] maps form field names to messages for the user. */
open class ValidationException(val errors: Map<String, String>) :
    IllegalArgumentException(errors.values.firstOrNull() ?: "Invalid input")

/** A name that another active row already has, ignoring case (CLARIFICATIONS.md D20). */
class DuplicateNameException(val field: String, message: String) : ValidationException(mapOf(field to message))

/** An operation that isn't allowed, such as deleting a system type. Shown to the user. */
class OperationNotAllowedException(message: String) : IllegalStateException(message)

/** Collects validation errors by field name. */
internal class Errors {
  private val errors = LinkedHashMap<String, String>()
  private var duplicateField: String? = null

  val isEmpty: Boolean
    get() = errors.isEmpty()

  fun add(field: String, message: String) {
    errors.putIfAbsent(field, message)
  }

  fun duplicate(field: String, message: String) {
    add(field, message)
    duplicateField = field
  }

  /** The trimmed value, or null after recording an error if it's blank or too long. */
  fun required(field: String, value: String?, label: String, maxLength: Int = Int.MAX_VALUE): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) {
      add(field, "$label is required")
      return null
    }
    if (text.length > maxLength) {
      add(field, "$label must be at most $maxLength characters")
      return null
    }
    return text
  }

  fun throwIfAny() {
    if (errors.isEmpty()) {
      return
    }
    val field = duplicateField
    if (field != null && errors.size == 1) {
      throw DuplicateNameException(field, errors.getValue(field))
    }
    throw ValidationException(LinkedHashMap(errors))
  }
}
