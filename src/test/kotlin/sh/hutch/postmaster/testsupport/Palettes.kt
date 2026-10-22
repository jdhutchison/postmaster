package sh.hutch.postmaster.testsupport

/**
 * The Dracula (dark) and Alucard (light) palettes, from the official spec at
 * https://draculatheme.com/spec (REQ User interface style: "Darcula and
 * Alucard should be used as the basis for the dark and light palettes";
 * CLAR Q18: Dracula and Alucard).
 */
object Palettes {
  val DRACULA: Map<String, String> = linkedMapOf(
      "background" to "#282A36",
      "selection" to "#44475A",
      "foreground" to "#F8F8F2",
      "comment" to "#6272A4",
      "red" to "#FF5555",
      "orange" to "#FFB86C",
      "yellow" to "#F1FA8C",
      "green" to "#50FA7B",
      "cyan" to "#8BE9FD",
      "purple" to "#BD93F9",
      "pink" to "#FF79C6",
      "floating" to "#343746",
      "background-lighter" to "#424450",
      "background-light" to "#343746",
      "background-dark" to "#21222C",
      "background-darker" to "#191A21"
  )

  val ALUCARD: Map<String, String> = linkedMapOf(
      "background" to "#FFFBEB",
      "selection" to "#CFCFDE",
      "foreground" to "#1F1F1F",
      "comment" to "#6C664B",
      "red" to "#CB3A2A",
      "orange" to "#A34D14",
      "yellow" to "#846E15",
      "green" to "#14710A",
      "cyan" to "#036A96",
      "purple" to "#644AC9",
      "pink" to "#A3144D",
      "floating" to "#EFEDDC",
      "background-lighter" to "#ECE9DF",
      "background-light" to "#DEDCCF",
      "background-dark" to "#CECCC0",
      "background-darker" to "#BCBAB3"
  )

  /** A `#rrggbb` colour as the `rgb(r, g, b)` string browsers report in computed styles */
  fun rgb(hex: String): String {
    val value = hex.removePrefix("#")
    val (r, g, b) = listOf(0, 2, 4).map { value.substring(it, it + 2).toInt(16) }
    return "rgb($r, $g, $b)"
  }
}
