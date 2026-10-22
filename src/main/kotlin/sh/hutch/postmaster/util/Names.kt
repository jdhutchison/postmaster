package sh.hutch.postmaster.util

import java.util.Locale

object Names {
  /**
   * The key that makes names unique regardless of case (CLARIFICATIONS.md D20), stored in the
   * name_key columns.
   */
  fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
}
