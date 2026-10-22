package sh.hutch.postmaster.models

/** An enum stored in the database as its [code]. */
interface CodedEnum {
  val code: String
}

/** Looks up an enum constant by its stored code. */
inline fun <reified E> codedEnumOf(code: String): E where E : Enum<E>, E : CodedEnum =
  enumValues<E>().firstOrNull { it.code == code }
      ?: throw IllegalArgumentException("Unknown ${E::class.simpleName} '$code'")
