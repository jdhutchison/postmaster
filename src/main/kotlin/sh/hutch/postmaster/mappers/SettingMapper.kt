package sh.hutch.postmaster.mappers

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import sh.hutch.postmaster.entities.SettingEntity
import sh.hutch.postmaster.models.Setting
import sh.hutch.postmaster.models.SettingType
import sh.hutch.postmaster.services.SettingsService

@Mapper
interface SettingMapper {
  @Mapping(target = "key", source = "settingKey")
  @Mapping(target = "type", source = "valueType")
  @Mapping(target = "allowedValues", expression = "java(SettingMappings.allowedValues(entity))")
  @Mapping(target = "value", expression = "java(SettingMappings.value(entity))")
  @Mapping(target = "displayValue", expression = "java(SettingMappings.displayValue(entity))")
  @Mapping(target = "hasValue", expression = "java(entity.getSettingValue() != null)")
  fun toModel(entity: SettingEntity): Setting
}

/** Derived values for [SettingMapper]. */
object SettingMappings {
  @JvmStatic
  fun allowedValues(entity: SettingEntity): List<String> =
    entity.allowedValues?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

  @JvmStatic
  fun value(entity: SettingEntity): String? =
    if (entity.valueType == SettingType.ENCRYPTED) null else entity.settingValue

  @JvmStatic
  fun displayValue(entity: SettingEntity): String =
    when {
      entity.settingValue == null -> ""
      entity.valueType == SettingType.ENCRYPTED -> SettingsService.MASK
      else -> entity.settingValue!!
    }
}
