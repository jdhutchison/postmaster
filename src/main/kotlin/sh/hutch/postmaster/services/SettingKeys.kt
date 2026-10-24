package sh.hutch.postmaster.services

/** Keys of the settings stored in the `Settings` table (seeded by V2__seed_settings.sql). */
object SettingKeys {
  const val PROCESSING_FREQUENCY = "processing.frequency"
  const val PROCESSING_LOOKBACK = "processing.lookback"
  const val PROCESSING_TIMEZONE = "processing.timezone"

  const val IMAP_HOST = "imap.host"
  const val IMAP_PORT = "imap.port"
  const val IMAP_SECURITY = "imap.security"
  const val IMAP_USERNAME = "imap.username"
  const val IMAP_PASSWORD = "imap.password"
  const val IMAP_INBOX_FOLDER = "imap.inbox_folder"
  const val IMAP_TRASH_FOLDER = "imap.trash_folder"

  const val SMTP_HOST = "smtp.host"
  const val SMTP_PORT = "smtp.port"
  const val SMTP_SECURITY = "smtp.security"
  const val SMTP_USERNAME = "smtp.username"
  const val SMTP_PASSWORD = "smtp.password"
  const val SMTP_FROM_ADDRESS = "smtp.from_address"

  const val LLM_URL = "llm.url"
  const val LLM_API_KEY = "llm.api_key"
  const val LLM_MODEL = "llm.model"
  const val LLM_TIMEOUT = "llm.timeout"
  const val LLM_MAX_INPUT_CHARS = "llm.max_input_chars"

  const val ATTACHMENTS_PATH = "attachments.path"
  const val ATTACHMENTS_EXTENSIONS = "attachments.extensions"

  const val EVENTS_DEFAULT_DURATION = "events.default_duration"

  const val FETCH_USER_AGENT = "fetch.user_agent"
  const val FETCH_TIMEOUT = "fetch.timeout"

  const val BILLS_PAY_LINK_TEXT = "bills.pay_link_text"
  const val BILLS_BILL_LINK_TEXT = "bills.bill_link_text"

  const val TODO_PATH = "todo.path"
  const val TODO_POSTMASTER_URL = "todo.postmaster_url"
}
