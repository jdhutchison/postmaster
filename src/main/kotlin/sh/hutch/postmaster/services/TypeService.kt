package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException
import sh.hutch.postmaster.entities.TypeEntity
import sh.hutch.postmaster.models.TypeInput
import sh.hutch.postmaster.repositories.TypeRepository
import sh.hutch.postmaster.util.Colours

/** Manages the types email can be classified as. */
@ApplicationScoped
@Transactional
class TypeService(private val types: TypeRepository) {

  fun list(): List<TypeEntity> = types.listActive()

  fun get(id: Long): TypeEntity = types.findActive(id) ?: throw NotFoundException("Type $id not found")

  fun create(input: TypeInput): TypeEntity {
    val type = TypeEntity()
    apply(type, input)
    types.persist(type)
    return type
  }

  fun update(id: Long, input: TypeInput): TypeEntity {
    val type = get(id)
    apply(type, input)
    return type
  }

  /** Soft-deletes the type. System types (Bill) can't be deleted (CLARIFICATIONS.md Q16). */
  fun delete(id: Long): TypeEntity {
    val type = get(id)
    if (type.isSystem) {
      throw OperationNotAllowedException("${type.name} is a system type and can't be deleted")
    }
    type.markDeleted()
    return type
  }

  private fun apply(type: TypeEntity, input: TypeInput) {
    val errors = Errors()
    val name = errors.required("name", input.name, "Name", MAX_NAME)
    if (name != null) {
      val other = types.findActiveByName(name)
      if (other != null && other.id != type.id) {
        errors.duplicate("name", "A type named '${other.name}' already exists")
      }
    }
    val colour = Colours.normalise(input.colour)
    if (colour == null) {
      errors.add("colour", "Colour must be an RGB hex code such as #ff5555")
    }
    errors.throwIfAny()
    type.name = name!!
    type.description = input.description?.trim()?.ifEmpty { null }
    type.colour = colour!!
  }

  companion object {
    const val MAX_NAME = 100
  }
}
