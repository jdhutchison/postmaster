package sh.hutch.postmaster.controllers

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.json.JsonMapper

/** How a toast message is shown: success and info fade away, errors stay until dismissed. */
enum class ToastLevel(val code: String) {
  SUCCESS("success"),
  INFO("info"),
  ERROR("error")
}

/** A message shown to the user as a toast. */
data class Toast(val level: ToastLevel, val message: String) {
  companion object {
    fun success(message: String) = Toast(ToastLevel.SUCCESS, message)

    fun info(message: String) = Toast(ToastLevel.INFO, message)

    fun error(message: String) = Toast(ToastLevel.ERROR, message)
  }
}

/** Raises toasts from HTMX responses through the `HX-Trigger` response header. */
object Toasts {
  const val HEADER = "HX-Trigger"

  /** Name of the event postmaster.js listens for. */
  const val EVENT = "pm-toast"

  // Non-ASCII characters are escaped so the header value stays ASCII; browsers read header
  // values as Latin-1
  private val json = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

  /** The `HX-Trigger` header value that shows [toasts]. */
  fun hxTrigger(vararg toasts: Toast): String = hxTrigger(toasts.toList(), emptyMap())

  /** The `HX-Trigger` header value that shows [toasts] and also triggers [events]. */
  fun hxTrigger(toasts: List<Toast>, events: Map<String, Any?>): String {
    val triggers = LinkedHashMap<String, Any?>(events)
    triggers[EVENT] = mapOf("toasts" to toasts.map { toJson(it) })
    return json.writeValueAsString(triggers)
  }

  internal fun toJson(toast: Toast): Map<String, String> = mapOf("level" to toast.level.code, "message" to toast.message)

  internal fun fromJson(value: Any?): Toast? {
    val map = value as? Map<*, *> ?: return null
    val level = ToastLevel.entries.firstOrNull { it.code == map["level"] } ?: return null
    val message = map["message"] as? String ?: return null
    return Toast(level, message)
  }

  internal fun mapper(): JsonMapper = json
}
