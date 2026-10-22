package sh.hutch.postmaster.models


/**
 * A setting as presented to users. [value] is always null for encrypted settings so their plain
 * text never leaves SettingsService; [displayValue] masks them instead.
 */
data class Setting(
    val key: String,
    val label: String,
    val type: SettingType,
    val allowedValues: List<String>,
    val description: String?,
    val value: String?,
    val displayValue: String,
    val hasValue: Boolean,
    val position: Int
)
