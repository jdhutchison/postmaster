package sh.hutch.postmaster.entities

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Table
import sh.hutch.postmaster.models.SettingType

@Entity
@Table(name = "Settings")
class SettingEntity : AuditedEntity() {
  @Column(name = "setting_key", nullable = false, length = 100)
  var settingKey: String = ""

  @Column(name = "label", nullable = false, length = 200)
  var label: String = ""

  /** The stored value; `enc:`-prefixed for encrypted settings, NULL when not set. */
  @Column(name = "setting_value")
  var settingValue: String? = null

  @Convert(converter = SettingTypeConverter::class)
  @Column(name = "value_type", nullable = false, length = 20)
  var valueType: SettingType = SettingType.TEXT

  /** Comma separated allowed values, for CHOICE settings. */
  @Column(name = "allowed_values", length = 500)
  var allowedValues: String? = null

  @Column(name = "description")
  var description: String? = null

  @Column(name = "position", nullable = false)
  var position: Int = 0
}
