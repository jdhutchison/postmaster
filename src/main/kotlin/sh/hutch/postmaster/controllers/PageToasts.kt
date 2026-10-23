package sh.hutch.postmaster.controllers

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Named

/**
 * Toasts to show on the page being rendered: those flashed by the previous request's redirect and
 * those added while handling this one. The layout renders [all].
 */
@RequestScoped
@Named("pageToasts")
class PageToasts(private val request: HttpServerRequest) {
  private val added = mutableListOf<Toast>()

  private val flashed: List<Toast> by lazy { Flash.decode(request.getCookie(Flash.COOKIE)?.value) }

  val all: List<Toast>
    get() = flashed + added

  fun add(toast: Toast) {
    added += toast
  }

  fun success(message: String) = add(Toast.success(message))

  fun info(message: String) = add(Toast.info(message))

  fun error(message: String) = add(Toast.error(message))
}
