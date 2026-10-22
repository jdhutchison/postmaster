package sh.hutch.postmaster.domain

import jakarta.persistence.AttributeConverter

/** An enum stored in the database as its [code]. */
interface CodedEnum {
  val code: String
}

/** Looks up an enum constant by its stored code. */
inline fun <reified E> codedEnumOf(code: String): E where E : Enum<E>, E : CodedEnum =
  enumValues<E>().firstOrNull { it.code == code }
      ?: throw IllegalArgumentException("Unknown ${E::class.simpleName} '$code'")

/** JPA converter storing a [CodedEnum] as its code. Subclass it for each enum. */
abstract class CodedEnumConverter<E>(private val type: Class<E>) : AttributeConverter<E, String>
    where E : Enum<E>, E : CodedEnum {

  override fun convertToDatabaseColumn(attribute: E?): String? = attribute?.code

  override fun convertToEntityAttribute(dbData: String?): E? =
    dbData?.let { code ->
      type.enumConstants.firstOrNull { it.code == code }
          ?: throw IllegalArgumentException("Unknown ${type.simpleName} '$code'")
    }
}
