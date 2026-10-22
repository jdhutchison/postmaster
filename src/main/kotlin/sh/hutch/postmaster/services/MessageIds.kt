package sh.hutch.postmaster.services

import java.security.MessageDigest
import java.time.Instant

/** Normalises Message-ID headers into the `Emails.message_id` form and hashes them. */
object MessageIds {
  /** Longest Message-ID stored; RFC 5322's line length limit. */
  const val MAX_LENGTH = 998

  const val GENERATED_PREFIX = "generated:"

  /**
   * Removes all whitespace (including from folded headers) and one pair of surrounding angle
   * brackets, and cuts the result to [MAX_LENGTH] characters. Returns null if nothing is left.
   */
  fun normalise(raw: String?): String? {
    if (raw == null) {
      return null
    }
    var id = raw.filterNot { it.isWhitespace() }
    if (id.length >= 2 && id.startsWith("<") && id.endsWith(">")) {
      id = id.substring(1, id.length - 1)
    }
    if (id.isBlank()) {
      return null
    }
    return if (id.length > MAX_LENGTH) id.substring(0, MAX_LENGTH) else id
  }

  /** An id for a message without a Message-ID header, derived from what identifies it. */
  fun generated(receivedAt: Instant?, from: String?, subject: String?, uidValidity: Long?, uid: Long?): String {
    val parts = listOf(receivedAt?.toString(), from, subject, uidValidity?.toString(), uid?.toString())
    return GENERATED_PREFIX + sha256Hex(parts.joinToString("\n") { it ?: "" })
  }

  /** Lower-case hex SHA-256 of the UTF-8 bytes of [messageId], stored in `Emails.message_hash`. */
  fun hash(messageId: String): String = sha256Hex(messageId)

  private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
