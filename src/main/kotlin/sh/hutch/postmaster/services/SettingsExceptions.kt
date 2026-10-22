package sh.hutch.postmaster.services

/** Thrown for a setting key that doesn't exist. */
class UnknownSettingException(val key: String) : NoSuchElementException("Unknown setting '$key'")

/** Thrown when a new value for a setting isn't valid for its type. The message is shown to users. */
class SettingValidationException(val key: String, message: String) : IllegalArgumentException(message)
