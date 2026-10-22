package sh.hutch.postmaster.entities

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import sh.hutch.postmaster.models.ActionResultStatus
import sh.hutch.postmaster.models.ActionSource
import sh.hutch.postmaster.models.CodedEnum
import sh.hutch.postmaster.models.ConditionAttribute
import sh.hutch.postmaster.models.EmailStatus
import sh.hutch.postmaster.models.ErrorPhase
import sh.hutch.postmaster.models.MatchType
import sh.hutch.postmaster.models.RuleType
import sh.hutch.postmaster.models.RunStatus
import sh.hutch.postmaster.models.RunTrigger
import sh.hutch.postmaster.models.SettingType

/** JPA converter storing a [CodedEnum] as its code. Subclass it for each enum. */
abstract class CodedEnumConverter<E>(private val type: Class<E>) : AttributeConverter<E, String>
    where E : Enum<E>, E : CodedEnum {

  override fun convertToDatabaseColumn(attribute: E?): String? = attribute?.code

  override fun convertToEntityAttribute(dbData: String?): E? =
    dbData?.let { code ->
      type.enumConstants.firstOrNull { it.code == code }
          ?: throw IllegalArgumentException("Unknown ${type.simpleName} '$code'")
    }
}

@Converter
class RuleTypeConverter : CodedEnumConverter<RuleType>(RuleType::class.java)

@Converter
class ConditionAttributeConverter : CodedEnumConverter<ConditionAttribute>(ConditionAttribute::class.java)

@Converter
class MatchTypeConverter : CodedEnumConverter<MatchType>(MatchType::class.java)

@Converter
class EmailStatusConverter : CodedEnumConverter<EmailStatus>(EmailStatus::class.java)

@Converter
class ErrorPhaseConverter : CodedEnumConverter<ErrorPhase>(ErrorPhase::class.java)

@Converter
class ActionSourceConverter : CodedEnumConverter<ActionSource>(ActionSource::class.java)

@Converter
class ActionResultStatusConverter : CodedEnumConverter<ActionResultStatus>(ActionResultStatus::class.java)

@Converter
class RunTriggerConverter : CodedEnumConverter<RunTrigger>(RunTrigger::class.java)

@Converter
class RunStatusConverter : CodedEnumConverter<RunStatus>(RunStatus::class.java)

@Converter
class SettingTypeConverter : CodedEnumConverter<SettingType>(SettingType::class.java)
