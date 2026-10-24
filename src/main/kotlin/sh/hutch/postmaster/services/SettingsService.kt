package sh.hutch.postmaster.services

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import sh.hutch.postmaster.entities.SettingEntity
import sh.hutch.postmaster.mappers.SettingMapper
import sh.hutch.postmaster.models.Setting
import sh.hutch.postmaster.models.SettingType
import sh.hutch.postmaster.repositories.SettingRepository
import sh.hutch.postmaster.util.InvalidDurationException
import sh.hutch.postmaster.util.IsoDurations
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId

/**
 * Reads and updates the settings stored in the database. Values are validated by their type, and
 * encrypted settings are encrypted before they are stored and decrypted when read.
 *
 * Its exceptions (a missing or unreadable setting, an invalid value) don't roll back the caller's
 * transaction: nothing has been changed when they're thrown, and processing records them as a
 * failed action and carries on, so the rest of the email's work must still be saved.
 */
@ApplicationScoped
@Transactional(dontRollbackOn = [RuntimeException::class])
class SettingsService(
    private val repository: SettingRepository,
    private val crypto: Crypto,
    private val mapper: SettingMapper
) {

  fun list(): List<Setting> = repository.listOrdered().map { mapper.toModel(it) }

  fun get(key: String): Setting = mapper.toModel(entity(key))

  /** The plain-text value, decrypted if the setting is encrypted; null if not set. */
  fun getString(key: String): String? {
    val entity = entity(key)
    val value = entity.settingValue ?: return null
    if (entity.valueType != SettingType.ENCRYPTED) {
      return value
    }
    return try {
      crypto.decrypt(value)
    } catch (e: CryptoException) {
      throw IllegalStateException("Setting '$key' could not be decrypted: ${e.message}", e)
    }
  }

  /** The keys of the encrypted settings that are set but can't be decrypted with this encryption key. */
  fun undecryptable(): List<String> =
    repository.listOrdered()
        .filter { it.valueType == SettingType.ENCRYPTED && it.settingValue != null }
        .filter { runCatching { crypto.decrypt(it.settingValue!!) }.isFailure }
        .map { it.settingKey }

  fun getInt(key: String): Int =
    required(key).let { it.toIntOrNull() ?: throw IllegalStateException("Setting '$key' is not an integer: '$it'") }

  fun getBool(key: String): Boolean =
    when (val value = required(key).lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw IllegalStateException("Setting '$key' is not true or false: '$value'")
    }

  fun getDuration(key: String): Duration =
    required(key).let {
      try {
        IsoDurations.parse(it)
      } catch (e: InvalidDurationException) {
        throw IllegalStateException("Setting '$key' is not a valid duration: '$it'", e)
      }
    }

  /** The items of a comma separated setting; empty if not set. */
  fun getList(key: String): List<String> =
    getString(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

  fun getZoneId(key: String): ZoneId =
    required(key).let {
      try {
        ZoneId.of(it)
      } catch (e: DateTimeException) {
        throw IllegalStateException("Setting '$key' is not a valid timezone: '$it'", e)
      }
    }

  /**
   * Validates [value] for the setting's type and stores it. A blank value for an encrypted setting
   * leaves it unchanged, because the masked field starts empty (CLARIFICATIONS.md Q17).
   *
   * @throws SettingValidationException with a message for the user if the value isn't valid
   */
  fun update(key: String, value: String): Setting {
    val entity = entity(key)
    if (entity.valueType == SettingType.ENCRYPTED) {
      if (value.isNotBlank()) {
        entity.settingValue = crypto.encrypt(value)
      }
    } else {
      entity.settingValue = validate(entity, value)
    }
    return mapper.toModel(entity)
  }

  /**
   * Removes the value of a text or encrypted setting.
   *
   * @throws SettingValidationException for settings of any other type, which must have a value
   */
  fun clear(key: String): Setting {
    val entity = entity(key)
    if (entity.valueType != SettingType.TEXT && entity.valueType != SettingType.ENCRYPTED) {
      throw SettingValidationException(key, "${entity.label} must have a value")
    }
    entity.settingValue = null
    return mapper.toModel(entity)
  }

  private fun entity(key: String): SettingEntity = repository.findByKey(key) ?: throw UnknownSettingException(key)

  private fun required(key: String): String =
    getString(key) ?: throw IllegalStateException("Setting '$key' has no value")

  /** Returns the value to store, or null to clear the setting. */
  private fun validate(entity: SettingEntity, value: String): String? {
    val key = entity.settingKey
    val text = value.trim()
    if (text.isEmpty()) {
      if (entity.valueType == SettingType.TEXT) {
        return null
      }
      throw SettingValidationException(key, "A value is required")
    }
    fun invalid(message: String): Nothing = throw SettingValidationException(key, message)

    return when (entity.valueType) {
      SettingType.TEXT -> text
      SettingType.INT -> {
        val number = text.toIntOrNull()
        when {
          number == null || number < 1 -> invalid("'$text' is not a positive whole number")
          key.endsWith(".port") && number > MAX_PORT -> invalid("'$text' is not a port number between 1 and $MAX_PORT")
          else -> number.toString()
        }
      }
      SettingType.BOOL -> when (text.lowercase()) {
        "true", "false" -> text.lowercase()
        else -> invalid("'$text' must be true or false")
      }
      SettingType.DURATION -> {
        val duration = try {
          IsoDurations.parse(text)
        } catch (e: InvalidDurationException) {
          invalid(e.message ?: "'$text' is not a valid duration")
        }
        when {
          duration <= Duration.ZERO -> invalid("The duration must be longer than zero")
          key == SettingKeys.PROCESSING_FREQUENCY && duration < MIN_FREQUENCY ->
            invalid("The processing frequency must be at least 1 minute")
          else -> text
        }
      }
      SettingType.PATH -> {
        val absolute = try {
          Path.of(text).isAbsolute
        } catch (e: InvalidPathException) {
          false
        }
        if (absolute) text else invalid("'$text' is not an absolute path")
      }
      SettingType.LIST -> {
        val items = text.split(',').map { it.trim() }
        if (items.any { it.isEmpty() }) invalid("List items can't be empty") else items.joinToString(", ")
      }
      SettingType.CHOICE -> {
        val allowed = entity.allowedValues?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        allowed.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: invalid("'$text' must be one of: ${allowed.joinToString(", ")}")
      }
      SettingType.TIMEZONE -> try {
        ZoneId.of(text).id
      } catch (e: DateTimeException) {
        invalid("'$text' is not a valid timezone, such as Australia/Melbourne")
      }
      SettingType.URL -> {
        val uri = try {
          URI(text)
        } catch (e: URISyntaxException) {
          null
        }
        val scheme = uri?.scheme?.lowercase()
        if (uri != null && (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) {
          text
        } else {
          invalid("'$text' is not an http or https URL")
        }
      }
      SettingType.ENCRYPTED -> error("Encrypted settings are handled by update()")
    }
  }

  companion object {
    /** Shown in place of the value of an encrypted setting that is set. */
    const val MASK = "••••••••"
    private const val MAX_PORT = 65535
    private val MIN_FREQUENCY = Duration.ofMinutes(1)
  }
}
