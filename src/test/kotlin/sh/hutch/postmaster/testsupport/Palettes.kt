package sh.hutch.postmaster.testsupport

/**
 * The Water.css light and dark palettes (https://watercss.kognise.dev), which
 * replaced Dracula and Alucard on 2026-10-24 (CLAR R4). Water.css has one
 * accent, its link blue; the status colours (red to pink) are Postmaster's.
 */
object Palettes {
  val WATER_DARK: Map<String, String> = linkedMapOf(
      "background" to "#202B38",
      "current-line" to "#526980",
      "selection" to "#526980",
      "foreground" to "#DBDBDB",
      "comment" to "#A9B1BA",
      "red" to "#FF6B6B",
      "orange" to "#FFBE85",
      "yellow" to "#EFDB43",
      "green" to "#6BD06F",
      "cyan" to "#41ADFF",
      "accent" to "#41ADFF",
      "pink" to "#D941E2",
      "floating" to "#1A242F",
      "background-lighter" to "#2A3949",
      "background-light" to "#161F27",
      "background-dark" to "#0C151C",
      "background-darker" to "#040A0F"
  )

  val WATER_LIGHT: Map<String, String> = linkedMapOf(
      "background" to "#FFFFFF",
      "current-line" to "#70777F",
      "selection" to "#DBDBDB",
      "foreground" to "#363636",
      "comment" to "#70777F",
      "red" to "#C62828",
      "orange" to "#B85C00",
      "yellow" to "#8A7400",
      "green" to "#2E8B32",
      "cyan" to "#0076D1",
      "accent" to "#0076D1",
      "pink" to "#B0279E",
      "floating" to "#F7F7F7",
      "background-lighter" to "#F2F2F2",
      "background-light" to "#EFEFEF",
      "background-dark" to "#DBDBDB",
      "background-darker" to "#9B9B9B"
  )

  /** The focus indicator colour (the same in both palettes) */
  const val FOCUS = "#0096BF"

  /** A `#rrggbb` colour as the `rgb(r, g, b)` string browsers report in computed styles */
  fun rgb(hex: String): String {
    val value = hex.removePrefix("#")
    val (r, g, b) = listOf(0, 2, 4).map { value.substring(it, it + 2).toInt(16) }
    return "rgb($r, $g, $b)"
  }
}
