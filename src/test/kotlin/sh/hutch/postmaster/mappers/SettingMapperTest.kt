package sh.hutch.postmaster.mappers

import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.hutch.postmaster.models.SettingType
import sh.hutch.postmaster.repositories.SettingRepository
import sh.hutch.postmaster.services.SettingsService

// REQ Coding style: MapStruct maps entities to models where the structure
// changes. The Setting model hides encrypted values (CLAR Q17).
@QuarkusTest
class SettingMapperTest {
  @Inject
  lateinit var mapper: SettingMapper

  @Inject
  lateinit var repository: SettingRepository

  @Inject
  lateinit var settings: SettingsService

  @Test
  @TestTransaction
  fun `maps a plain setting`() {
    val setting = mapper.toModel(repository.findByKey("imap.security")!!)

    assertEquals("imap.security", setting.key)
    assertEquals("Dovecot connection security", setting.label)
    assertEquals(SettingType.CHOICE, setting.type)
    assertEquals(listOf("none", "starttls", "tls"), setting.allowedValues)
    assertEquals("none", setting.value)
    assertEquals("none", setting.displayValue)
    assertTrue(setting.hasValue)
    assertTrue(setting.description!!.isNotBlank())
  }

  @Test
  @TestTransaction
  fun `maps an encrypted setting without its value`() {
    settings.update("imap.password", "mapped-secret")

    val setting = mapper.toModel(repository.findByKey("imap.password")!!)

    assertEquals(SettingType.ENCRYPTED, setting.type)
    assertNull(setting.value)
    assertEquals(SettingsService.MASK, setting.displayValue)
    assertTrue(setting.hasValue)
  }

  @Test
  @TestTransaction
  fun `maps an unset setting`() {
    val setting = mapper.toModel(repository.findByKey("imap.username")!!)

    assertNull(setting.value)
    assertEquals("", setting.displayValue)
    assertFalse(setting.hasValue)
    assertEquals(emptyList<String>(), setting.allowedValues)
  }
}
