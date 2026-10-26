package sh.hutch.postmaster.services

import io.agroal.api.AgroalDataSource
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import sh.hutch.postmaster.models.SettingType
import sh.hutch.postmaster.repositories.SettingRepository
import sh.hutch.postmaster.testsupport.row
import sh.hutch.postmaster.testsupport.update
import java.time.Duration
import java.time.ZoneId

/**
 * Settings stored in the database (REQ Configuration; CLAR D9, Q17) and the
 * service that reads, validates and encrypts them (docs/dev/contracts.md).
 * Every test that changes a setting runs in a rolled-back transaction.
 */
@QuarkusTest
class SettingsServiceTest {
  @Inject
  lateinit var settings: SettingsService

  @Inject
  lateinit var repository: SettingRepository

  @Inject
  lateinit var crypto: Crypto

  @Inject
  lateinit var dataSource: AgroalDataSource

  @Inject
  lateinit var entityManager: EntityManager

  // Reading

  @Test
  fun `lists every setting in display order`() {
    // CLAR D9: the set of settings is fixed and seeded; R5 adds the two to-do list settings and
    // R7 the two status check thresholds, and R9 four frequencies and the tracking window
    val list = settings.list()

    assertEquals(36, list.size)
    assertEquals(list.sortedBy { it.position }, list)
    assertEquals("processing.frequency", list.first().key)
  }

  @Test
  fun `typed getters read the seeded defaults`() {
    assertEquals(Duration.ofHours(1), settings.getDuration("processing.frequency"), "REQ Fetching emails: 1h")
    assertEquals(Duration.ofDays(7), settings.getDuration("processing.lookback"), "CLAR Q14")
    assertEquals(ZoneId.of("Australia/Melbourne"), settings.getZoneId("processing.timezone"), "CLAR D8")
    assertEquals(143, settings.getInt("imap.port"))
    assertEquals(listOf("pdf"), settings.getList("attachments.extensions"))
    assertEquals("Trash", settings.getString("imap.trash_folder"), "CLAR Q6")
    assertNull(settings.getString("imap.username"))
  }

  @Test
  fun `an unknown key is reported`() {
    assertThrows<UnknownSettingException> { settings.get("no.such.setting") }
    assertThrows<UnknownSettingException> { settings.getString("no.such.setting") }
    assertThrows<NoSuchElementException> { settings.update("no.such.setting", "x") }
  }

  @Test
  @TestTransaction
  fun `a stored value that doesn't parse is an error, not a default`() {
    dataSource.update("UPDATE Settings SET setting_value = 'soon' WHERE setting_key = 'processing.frequency'")

    assertThrows<IllegalStateException> { settings.getDuration("processing.frequency") }
  }

  @Test
  @TestTransaction
  fun `the renamed key and value columns work through the repository and the service`() {
    // Coordinator request / CLAR D20: Settings.key and value became setting_key and setting_value
    assertEquals("qwen3.8-9b-100k", repository.findByKey("llm.model")!!.settingValue)

    settings.update("llm.model", "another-model")

    entityManager.flush()
    assertEquals("another-model", repository.findByKey("llm.model")!!.settingValue)
    assertEquals("another-model", settings.getString("llm.model"))
    assertEquals(
        "another-model",
        dataSource.row("SELECT setting_value FROM Settings WHERE setting_key = 'llm.model'")["setting_value"]
    )
  }

  // Encrypted settings

  @Test
  @TestTransaction
  fun `an updated encrypted setting is stored encrypted and read back as plain text`() {
    // REQ Configuration 3, Fetching emails ("The password will be AES encrypted"), Configuration (enc: prefix)
    settings.update("imap.password", "dovecot-secret")
    entityManager.flush()

    val row = dataSource.row("SELECT setting_value FROM Settings WHERE setting_key = 'imap.password'")
    val stored = row["setting_value"] as String
    assertTrue(stored.startsWith("enc:"), stored)
    assertFalse(stored.contains("dovecot-secret"))
    assertEquals("dovecot-secret", crypto.decrypt(stored))
    assertEquals("dovecot-secret", settings.getString("imap.password"))
  }

  @Test
  @TestTransaction
  fun `an encrypted setting is displayed masked and never exposes its value`() {
    // CLAR Q17: masked rather than shown; the plain text never leaves the service
    settings.update("llm.api_key", "llm-key")

    val setting = settings.get("llm.api_key")
    assertEquals(SettingType.ENCRYPTED, setting.type)
    assertEquals(SettingsService.MASK, setting.displayValue)
    assertEquals("••••••••", SettingsService.MASK)
    assertNull(setting.value)
    assertTrue(setting.hasValue)
    assertTrue(settings.list().none { it.value == "llm-key" || it.displayValue == "llm-key" })
  }

  @Test
  @TestTransaction
  fun `an encrypted value is kept exactly as entered`() {
    // docs/dev/contracts.md: passwords may start or end with spaces, so they aren't trimmed
    settings.update("imap.password", "  spaced secret  ")

    assertEquals("  spaced secret  ", settings.getString("imap.password"))
  }

  @Test
  @TestTransaction
  fun `an encrypted value that can't be decrypted is an error`() {
    dataSource.update("UPDATE Settings SET setting_value = 'enc:not-valid' WHERE setting_key = 'imap.password'")

    assertThrows<IllegalStateException> { settings.getString("imap.password") }
  }

  @Test
  fun `an unset encrypted setting displays as empty`() {
    val setting = settings.get("smtp.password")

    assertEquals("", setting.displayValue)
    assertFalse(setting.hasValue)
  }

  @Test
  @TestTransaction
  fun `a blank value leaves an encrypted setting unchanged`() {
    // CLAR Q17: the masked field starts empty, so saving it empty keeps the old value
    settings.update("imap.password", "kept-secret")

    settings.update("imap.password", "")
    settings.update("imap.password", "   ")

    assertEquals("kept-secret", settings.getString("imap.password"))
  }

  @Test
  @TestTransaction
  fun `an encrypted setting can be cleared`() {
    settings.update("smtp.password", "smtp-secret")

    settings.clear("smtp.password")

    assertNull(settings.getString("smtp.password"))
    assertFalse(settings.get("smtp.password").hasValue)
  }

  // Durations (REQ Configuration 4)

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = ["PT1H", "1H", "1h", "PT30M", "2h"])
  @TestTransaction
  fun `frequency accepts full and simplified ISO 8601 durations`(value: String) {
    settings.update("processing.frequency", value)

    assertEquals(value, settings.get("processing.frequency").value)
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = ["1 hour", "hourly", "P1M", "0h", "PT0S", "30s", ""])
  @TestTransaction
  fun `frequency rejects anything else, zero, and less than a minute`(value: String) {
    val error = assertThrows<SettingValidationException> { settings.update("processing.frequency", value) }

    assertTrue(error.message!!.isNotBlank())
    assertEquals(Duration.ofHours(1), settings.getDuration("processing.frequency"), "value unchanged")
  }

  // Other types (CLAR D9; docs/dev/contracts.md)

  @ParameterizedTest(name = "{0} = \"{1}\"")
  @CsvSource(
      "imap.port, abc",
      "imap.port, 0",
      "imap.port, 70000",
      "imap.port, -1",
      "llm.max_input_chars, 0",
      "llm.max_input_chars, 1.5",
      "imap.security, ssl",
      "processing.timezone, Mars/Olympus",
      "llm.url, ftp://kodiakbear/v1",
      "llm.url, not a url",
      "attachments.path, relative/path",
      "attachments.extensions, 'pdf, , ics'"
  )
  @TestTransaction
  fun `invalid values are rejected with a validation error`(key: String, value: String) {
    val before = settings.get(key).value

    val error = assertThrows<SettingValidationException> { settings.update(key, value) }

    assertTrue(error.message!!.isNotBlank())
    assertEquals(before, settings.get(key).value, "$key unchanged")
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = ["imap.port", "processing.timezone", "llm.url", "attachments.path", "attachments.extensions"])
  @TestTransaction
  fun `a value is required for settings that aren't text or encrypted`(key: String) {
    assertThrows<SettingValidationException> { settings.update(key, "") }
  }

  @Test
  @TestTransaction
  fun `valid values are stored`() {
    settings.update("imap.port", "993")
    settings.update("processing.timezone", "Europe/London")
    settings.update("llm.url", "https://llm.example/v1")
    settings.update("attachments.path", "/srv/attachments")

    assertEquals(993, settings.getInt("imap.port"))
    assertEquals(ZoneId.of("Europe/London"), settings.getZoneId("processing.timezone"))
    assertEquals("https://llm.example/v1", settings.getString("llm.url"))
    assertEquals("/srv/attachments", settings.getString("attachments.path"))
  }

  @Test
  @TestTransaction
  fun `values are stored trimmed`() {
    settings.update("llm.model", "  padded-model  ")

    assertEquals("padded-model", settings.getString("llm.model"))
  }

  @Test
  @TestTransaction
  fun `a choice is stored in its allowed spelling`() {
    settings.update("imap.security", "STARTTLS")

    assertEquals("starttls", settings.get("imap.security").value)
    assertEquals(listOf("none", "starttls", "tls"), settings.get("imap.security").allowedValues)
  }

  @Test
  @TestTransaction
  fun `a list is stored as comma plus space separated items`() {
    // REQ Configuration: "Attachment extensions to process (a comma separated list)"
    settings.update("attachments.extensions", "pdf,ics ,  docx")

    assertEquals("pdf, ics, docx", settings.get("attachments.extensions").value)
    assertEquals(listOf("pdf", "ics", "docx"), settings.getList("attachments.extensions"))
  }

  @Test
  @TestTransaction
  fun `a blank text setting is stored as not set`() {
    settings.update("smtp.from_address", "postmaster@example.test")

    settings.update("smtp.from_address", "  ")

    assertNull(settings.getString("smtp.from_address"))
  }

  @Test
  @TestTransaction
  fun `only text and encrypted settings can be cleared`() {
    settings.clear("smtp.username")
    assertNull(settings.getString("smtp.username"))

    assertThrows<SettingValidationException> { settings.clear("imap.port") }
    assertThrows<SettingValidationException> { settings.clear("processing.frequency") }
    assertEquals(143, settings.getInt("imap.port"))
  }

  @Test
  @TestTransaction
  fun `the validation error names the setting`() {
    val error = assertThrows<SettingValidationException> { settings.update("imap.port", "abc") }

    assertEquals("imap.port", error.key)
  }
}
