package sh.hutch.postmaster.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

// CLAR Q14: email is recognised by its Message-ID, so already-processed mail
// isn't processed again; CLAR D7 (message_id) and D20 (unique on a hash).
// Normalisation rules are in docs/dev/contracts.md (MessageIds).
class MessageIdsTest {
  @Test
  fun `angle brackets are removed`() {
    assertEquals("abc.123@mail.example", MessageIds.normalise("<abc.123@mail.example>"))
    assertEquals("abc.123@mail.example", MessageIds.normalise("abc.123@mail.example"))
  }

  @Test
  fun `whitespace is removed, including from folded headers`() {
    assertEquals("abc.123@mail.example", MessageIds.normalise("  <abc.123@\r\n mail.example>  "))
    assertEquals("abc.123@mail.example", MessageIds.normalise("<abc .123@mail.example>\t"))
  }

  @Test
  fun `only one pair of surrounding brackets is removed`() {
    assertEquals("<abc@mail.example>", MessageIds.normalise("<<abc@mail.example>>"))
  }

  @Test
  fun `case is kept`() {
    assertEquals("AbC@Mail.Example", MessageIds.normalise("<AbC@Mail.Example>"))
  }

  @Test
  fun `a missing or blank Message-ID normalises to null`() {
    assertNull(MessageIds.normalise(null))
    assertNull(MessageIds.normalise(""))
    assertNull(MessageIds.normalise("   "))
    assertNull(MessageIds.normalise("<>"))
    assertNull(MessageIds.normalise(" < > "))
  }

  @Test
  fun `a maximum-length Message-ID is kept whole and anything longer is cut to 998 characters`() {
    // Coordinator request: a realistic maximum Message-ID is 998 characters
    val maximum = "m".repeat(985) + "@example.test"
    assertEquals(998, maximum.length)

    assertEquals(maximum, MessageIds.normalise("<$maximum>"))
    assertEquals(998, MessageIds.normalise("x".repeat(1500))!!.length)
  }

  @Test
  fun `the hash is the lower-case hex SHA-256 of the UTF-8 bytes`() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MessageIds.hash("abc"))
    assertEquals(sha256("é@exämple.test"), MessageIds.hash("é@exämple.test"))
    assertEquals(64, MessageIds.hash("m".repeat(998)).length)
  }

  @Test
  fun `the same Message-ID always gives the same hash, and different ones differ`() {
    val id = "m".repeat(985) + "@example.test"

    assertEquals(MessageIds.hash(id), MessageIds.hash(MessageIds.normalise("<$id>")!!))
    assertNotEquals(MessageIds.hash(id), MessageIds.hash(id.uppercase()))
  }

  @Test
  fun `a generated id hashes the message's details with nulls as empty`() {
    // Used when a message has no Message-ID, so it is still recognised next time
    assertEquals("generated:" + sha256("\n\n\n\n"), MessageIds.generated(null, null, null, null, null))
    assertEquals(
        MessageIds.generated(null, null, null, null, null),
        MessageIds.generated(null, "", "", null, null)
    )
  }

  @Test
  fun `generated ids are stable and depend on every detail`() {
    val received = Instant.parse("2026-10-22T09:15:00Z")
    val base = MessageIds.generated(received, "a@b.example", "Hello", 1L, 42L)

    assertTrue(Regex("^generated:[0-9a-f]{64}$").matches(base), base)
    assertEquals(base, MessageIds.generated(received, "a@b.example", "Hello", 1L, 42L))
    val variants = listOf(
        MessageIds.generated(received.plusSeconds(1), "a@b.example", "Hello", 1L, 42L),
        MessageIds.generated(received, "c@b.example", "Hello", 1L, 42L),
        MessageIds.generated(received, "a@b.example", "Hello!", 1L, 42L),
        MessageIds.generated(received, "a@b.example", "Hello", 2L, 42L),
        MessageIds.generated(received, "a@b.example", "Hello", 1L, 43L)
    )
    assertEquals(5, variants.toSet().size)
    assertTrue(base !in variants)
  }

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256")
          .digest(text.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }
}
