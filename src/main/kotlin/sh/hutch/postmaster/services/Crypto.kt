package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import sh.hutch.postmaster.config.PostmasterConfig
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a value can't be decrypted. */
class CryptoException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * AES-256-GCM encryption for settings stored in the database. Encrypted values are `enc:` followed
 * by the base64 of a random 12 byte IV and the ciphertext with its 128 bit authentication tag.
 */
@ApplicationScoped
class Crypto(config: PostmasterConfig) {
  private val key = SecretKeySpec(decodeKey(config.encryptionKey()), "AES")
  private val random = SecureRandom()

  fun encrypt(plainText: String): String {
    val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    return PREFIX + Base64.getEncoder().encodeToString(iv + cipherText)
  }

  /**
   * @throws CryptoException if [value] isn't `enc:`-prefixed, isn't valid base64, was tampered
   *     with, or was encrypted with a different key
   */
  fun decrypt(value: String): String {
    if (!isEncrypted(value)) {
      throw CryptoException("Value is not encrypted: it doesn't start with '$PREFIX'")
    }
    val bytes = try {
      Base64.getDecoder().decode(value.substring(PREFIX.length))
    } catch (e: IllegalArgumentException) {
      throw CryptoException("Encrypted value is not valid base64", e)
    }
    if (bytes.size < IV_BYTES + TAG_BITS / 8) {
      throw CryptoException("Encrypted value is too short")
    }
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
      String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      throw CryptoException("Value could not be decrypted: it was changed or encrypted with a different key", e)
    }
  }

  fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

  companion object {
    const val PREFIX = "enc:"
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Decodes the `postmaster.encryption-key` setting.
     *
     * @throws IllegalArgumentException unless [base64] is valid base64 of exactly 32 bytes
     */
    fun decodeKey(base64: String): ByteArray {
      val bytes = try {
        Base64.getDecoder().decode(base64.trim())
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("postmaster.encryption-key is not valid base64", e)
      }
      require(bytes.size == KEY_BYTES) {
        "postmaster.encryption-key must be the base64 encoding of exactly $KEY_BYTES bytes (AES-256) but is ${bytes.size} bytes"
      }
      return bytes
    }
  }
}
