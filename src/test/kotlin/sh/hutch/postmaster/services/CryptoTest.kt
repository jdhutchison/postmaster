package sh.hutch.postmaster.services

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// REQ Configuration 3 and "Configuration" (values stored encrypted are
// prefixed with enc:), REQ Fetching emails (password AES encrypted), CLAR S7
// (AES-256-GCM, random IV, enc: + base64) and docs/dev/contracts.md
// (base64 of 12-byte IV followed by ciphertext and 128-bit tag).
@QuarkusTest
class CryptoTest {
  @Inject
  lateinit var crypto: Crypto

  @Test
  fun `encrypted values carry the enc prefix and hide the plain text`() {
    val encrypted = crypto.encrypt("dovecot-password")

    assertTrue(encrypted.startsWith("enc:"), encrypted)
    assertEquals("enc:", Crypto.PREFIX)
    assertFalse(encrypted.contains("dovecot-password"))
  }

  @Test
  fun `decrypting returns the original text`() {
    for (text in listOf("p", "dovecot-password", "pässwörd ✓ 密码", "x".repeat(5000))) {
      assertEquals(text, crypto.decrypt(crypto.encrypt(text)))
    }
  }

  @Test
  fun `each encryption uses a fresh random IV`() {
    assertNotEquals(crypto.encrypt("same text"), crypto.encrypt("same text"))
  }

  @Test
  fun `values are AES-256-GCM with a 12 byte IV and 128 bit tag, keyed by the configured key`() {
    val encrypted = crypto.encrypt("interoperable")

    val bytes = Base64.getDecoder().decode(encrypted.removePrefix("enc:"))
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, TEST_KEY, GCMParameterSpec(128, bytes, 0, 12))
    assertEquals("interoperable", String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
  }

  @Test
  fun `decrypts values written in the documented format`() {
    assertEquals("written elsewhere", crypto.decrypt(encryptWith(TEST_KEY, "written elsewhere")))
  }

  @Test
  fun `rejects values encrypted with a different key`() {
    val otherKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    assertThrows<CryptoException> { crypto.decrypt(encryptWith(otherKey, "secret")) }
  }

  @Test
  fun `rejects tampered values`() {
    val bytes = Base64.getDecoder().decode(crypto.encrypt("secret").removePrefix("enc:"))
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

    assertThrows<CryptoException> { crypto.decrypt("enc:" + Base64.getEncoder().encodeToString(bytes)) }
  }

  @Test
  fun `rejects values without the prefix or with bad base64`() {
    val valid = crypto.encrypt("secret")

    assertThrows<CryptoException> { crypto.decrypt(valid.removePrefix("enc:")) }
    assertThrows<CryptoException> { crypto.decrypt("enc:not base64!!") }
    assertThrows<CryptoException> { crypto.decrypt("enc:") }
    assertThrows<IllegalArgumentException> { crypto.decrypt("plain") }
  }

  @Test
  fun `recognises encrypted values by their prefix`() {
    assertTrue(crypto.isEncrypted(crypto.encrypt("x")))
    assertTrue(crypto.isEncrypted("enc:anything"))
    assertFalse(crypto.isEncrypted("plain"))
    assertFalse(crypto.isEncrypted(null))
  }

  @Test
  fun `the key must be base64 of exactly 32 bytes`() {
    // docs/dev/contracts.md postmaster.encryption-key
    val encoder = Base64.getEncoder()
    val key = ByteArray(32) { it.toByte() }
    assertArrayEquals(key, Crypto.decodeKey(encoder.encodeToString(key)))

    assertThrows<IllegalArgumentException> { Crypto.decodeKey(encoder.encodeToString(ByteArray(16))) }
    assertThrows<IllegalArgumentException> { Crypto.decodeKey(encoder.encodeToString(ByteArray(31))) }
    assertThrows<IllegalArgumentException> { Crypto.decodeKey(encoder.encodeToString(ByteArray(33))) }
    assertThrows<IllegalArgumentException> { Crypto.decodeKey("not base64!!") }
    assertThrows<IllegalArgumentException> { Crypto.decodeKey("") }
  }

  private fun encryptWith(key: SecretKeySpec, text: String): String {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    return "enc:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
  }

  companion object {
    /** The key in src/test/resources/application.yaml */
    private val TEST_KEY = SecretKeySpec("0123456789abcdef0123456789abcdef".toByteArray(), "AES")
  }
}
