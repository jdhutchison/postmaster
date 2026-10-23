package sh.hutch.postmaster.util

object Colours {
  private val HEX = Regex("#[0-9a-fA-F]{6}")

  /** The colour as lower-case `#rrggbb`, or null if it isn't one. */
  fun normalise(text: String?): String? = text?.trim()?.takeIf { HEX.matches(it) }?.lowercase()
}
