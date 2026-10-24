package sh.hutch.postmaster.util

import org.jboss.logging.MDC

/**
 * The logging context (MDC) keys, and a helper that sets them for a block of code. Log lines show
 * the context with `%X` (see `quarkus.log.console.format`).
 */
object Mdc {
  /** The processing run's id. */
  const val RUN = "run"

  /** The email's id. */
  const val EMAIL = "email"

  /** The message's Message-ID, while it's fetched and before the email is stored. */
  const val MESSAGE_ID = "message_id"

  /** The processing phase: `classification`, `llm` or `action`. */
  const val PHASE = "phase"

  /** The name of the rule being applied. */
  const val RULE = "rule"

  /** The action type being carried out. */
  const val ACTION = "action"

  /** Where a manual action came from: `ui` or `mcp`. */
  const val SOURCE = "source"

  /**
   * Runs [block] with [entries] in the MDC, then puts back what was there before. A null value
   * removes the key for the block.
   */
  inline fun <T> with(vararg entries: Pair<String, Any?>, block: () -> T): T {
    val previous = entries.map { (key, _) -> key to MDC.get(key) }
    for ((key, value) in entries) {
      if (value == null) MDC.remove(key) else MDC.put(key, value.toString())
    }
    try {
      return block()
    } finally {
      for ((key, value) in previous) {
        if (value == null) MDC.remove(key) else MDC.put(key, value)
      }
    }
  }
}
