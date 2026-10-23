package sh.hutch.postmaster.controllers

import jakarta.ws.rs.core.Response

/** Builds responses to HTMX requests: a fragment plus toasts and other client events. */
object Htmx {
  const val RETARGET = "HX-Retarget"
  const val RESWAP = "HX-Reswap"
  const val REDIRECT = "HX-Redirect"
  const val TRIGGER_AFTER_SWAP = "HX-Trigger-After-Swap"
  const val REQUEST = "HX-Request"

  fun response(
      entity: Any?,
      status: Int = 200,
      toasts: List<Toast> = emptyList(),
      events: Map<String, Any?> = emptyMap(),
      retarget: String? = null,
      reswap: String? = null
  ): Response {
    val builder = Response.status(status).entity(entity)
    if (toasts.isNotEmpty() || events.isNotEmpty()) {
      builder.header(Toasts.HEADER, Toasts.hxTrigger(toasts, events))
    }
    retarget?.let { builder.header(RETARGET, it) }
    reswap?.let { builder.header(RESWAP, it) }
    return builder.build()
  }

  /** A form fragment that opens [dialogId] once it has been swapped into the dialog's body. */
  fun dialogForm(entity: Any, dialogId: String): Response =
    Response.ok(entity).header(TRIGGER_AFTER_SWAP, Toasts.mapper().writeValueAsString(openDialog(dialogId))).build()

  fun openDialog(dialogId: String): Map<String, Any?> = mapOf("pm-dialog-open" to mapOf("id" to dialogId))

  fun closeDialog(dialogId: String): Map<String, Any?> = mapOf("pm-dialog-close" to mapOf("id" to dialogId))

  /** Makes HTMX load [location] (a path under the root path) as a full page, with flashed toasts. */
  fun redirect(location: String, vararg toasts: Toast): Response {
    val target = Flash.resolve(location)
    val builder = Response.ok().header(REDIRECT, target)
    if (toasts.isNotEmpty()) {
      builder.cookie(Flash.cookie(toasts.toList()))
    }
    return builder.build()
  }
}
