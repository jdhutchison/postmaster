-- Postmaster schema.
--
-- Table names are Snake_case with a leading capital and columns are lower snake case
-- (REQUIREMENTS.md, coding style). Identifiers are never quoted, so they must be written
-- consistently: MariaDB on Linux treats table names as case-sensitive.
--
-- No identifier is a reserved word in MariaDB or H2 2.x, and none is quoted, so the same SQL
-- runs on MariaDB and on H2 (MariaDB mode) in tests (CLARIFICATIONS.md D20). The requirements'
-- `key` and `value` (Settings) became `setting_key` and `setting_value`, and Email_events
-- `timestamp` became `starts_at`.
--
-- Times are DATETIME(6) holding UTC, except Email_events starts_at/ends_at, which are local
-- wall-clock times in the processing.timezone setting.
--
-- Names that must be unique regardless of case (D20) have a name_key column: the lower-cased name
-- while the row is active and NULL once it is soft-deleted. It uses a binary collation, so the
-- unique constraint behaves the same on MariaDB (whose default collation would also fold
-- accents) as on H2, and NULLs let deleted rows keep their names.
--
-- ${table_options} is a Flyway placeholder (quarkus.flyway.placeholders.table_options).

CREATE TABLE Settings (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  setting_key VARCHAR(100) NOT NULL,
  label VARCHAR(200) NOT NULL,
  setting_value TEXT NULL,
  value_type VARCHAR(20) NOT NULL,
  allowed_values VARCHAR(500) NULL,
  description TEXT NULL,
  position INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  CONSTRAINT uq_settings_setting_key UNIQUE (setting_key)
) ${table_options};

CREATE TABLE Types (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  system_key VARCHAR(50) NULL,
  name VARCHAR(100) NOT NULL,
  name_key VARCHAR(100) COLLATE utf8mb4_bin NULL,
  description TEXT NULL,
  colour VARCHAR(7) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT uq_types_system_key UNIQUE (system_key),
  CONSTRAINT uq_types_name_key UNIQUE (name_key)
) ${table_options};

CREATE TABLE Calendars (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  name_key VARCHAR(100) COLLATE utf8mb4_bin NULL,
  description TEXT NOT NULL,
  colour VARCHAR(7) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  notification_periods VARCHAR(255) NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT uq_calendars_name_key UNIQUE (name_key)
) ${table_options};

CREATE TABLE Prompts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  prompt_key VARCHAR(50) NULL,
  name VARCHAR(100) NOT NULL,
  contents LONGTEXT NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT uq_prompts_prompt_key UNIQUE (prompt_key)
) ${table_options};

CREATE TABLE Rules (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  name_key VARCHAR(100) COLLATE utf8mb4_bin NULL,
  description TEXT NOT NULL,
  rule_type VARCHAR(20) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT uq_rules_type_name_key UNIQUE (rule_type, name_key)
) ${table_options};

CREATE INDEX idx_rules_type_priority ON Rules (rule_type, priority);

CREATE TABLE Conditions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_id BIGINT NOT NULL,
  attribute VARCHAR(20) NOT NULL,
  match_type VARCHAR(20) NOT NULL,
  test VARCHAR(1000) NULL,
  position INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_conditions_rule FOREIGN KEY (rule_id) REFERENCES Rules (id) ON DELETE CASCADE
) ${table_options};

CREATE TABLE Rule_actions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_id BIGINT NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  destination VARCHAR(500) NULL,
  type_id BIGINT NULL,
  calendar_id BIGINT NULL,
  notifications VARCHAR(255) NULL,
  position INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT fk_rule_actions_rule FOREIGN KEY (rule_id) REFERENCES Rules (id) ON DELETE CASCADE,
  CONSTRAINT fk_rule_actions_type FOREIGN KEY (type_id) REFERENCES Types (id),
  CONSTRAINT fk_rule_actions_calendar FOREIGN KEY (calendar_id) REFERENCES Calendars (id)
) ${table_options};

CREATE TABLE Emails (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id VARCHAR(998) NOT NULL,
  message_hash CHAR(64) NOT NULL,
  imap_uid BIGINT NULL,
  uid_validity BIGINT NULL,
  folder VARCHAR(255) NULL,
  subject TEXT NULL,
  sender VARCHAR(320) NULL,
  sender_name VARCHAR(255) NULL,
  type_id BIGINT NULL,
  status VARCHAR(20) NOT NULL,
  message LONGTEXT NULL,
  html_message LONGTEXT NULL,
  summary LONGTEXT NULL,
  link VARCHAR(2000) NULL,
  link_contents LONGTEXT NULL,
  link_summary LONGTEXT NULL,
  bill_sender VARCHAR(255) NULL,
  bill_due_date DATE NULL,
  bill_amount DECIMAL(12, 2) NULL,
  bill_pay_link VARCHAR(2000) NULL,
  bill_download_url VARCHAR(2000) NULL,
  response_required BOOLEAN NOT NULL DEFAULT FALSE,
  action_required BOOLEAN NOT NULL DEFAULT FALSE,
  action_description TEXT NULL,
  moved_to VARCHAR(255) NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  received_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6) NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  CONSTRAINT uq_emails_message_hash UNIQUE (message_hash),
  CONSTRAINT fk_emails_type FOREIGN KEY (type_id) REFERENCES Types (id)
) ${table_options};

CREATE INDEX idx_emails_received_at ON Emails (received_at);
CREATE INDEX idx_emails_status ON Emails (status);
CREATE INDEX idx_emails_sender ON Emails (sender);

CREATE TABLE Email_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  calendar_id BIGINT NULL,
  title VARCHAR(500) NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NULL,
  all_day BOOLEAN NOT NULL DEFAULT FALSE,
  location VARCHAR(500) NULL,
  notes TEXT NULL,
  is_added BOOLEAN NOT NULL DEFAULT FALSE,
  khal_uid VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_events_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE,
  CONSTRAINT fk_email_events_calendar FOREIGN KEY (calendar_id) REFERENCES Calendars (id)
) ${table_options};

CREATE TABLE Email_attachments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  filename VARCHAR(500) NOT NULL,
  content_type VARCHAR(255) NULL,
  size_bytes BIGINT NULL,
  part_path VARCHAR(100) NULL,
  stored_path VARCHAR(1000) NULL,
  downloaded_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_attachments_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE
) ${table_options};

CREATE TABLE Email_rule_matches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  rule_type VARCHAR(20) NOT NULL,
  position INT NOT NULL DEFAULT 0,
  matched_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_rule_matches_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE,
  CONSTRAINT fk_email_rule_matches_rule FOREIGN KEY (rule_id) REFERENCES Rules (id)
) ${table_options};

CREATE TABLE Email_action_results (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  rule_id BIGINT NULL,
  rule_action_id BIGINT NULL,
  action_type VARCHAR(40) NOT NULL,
  source VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  detail TEXT NULL,
  executed_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_action_results_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE,
  CONSTRAINT fk_email_action_results_rule FOREIGN KEY (rule_id) REFERENCES Rules (id),
  CONSTRAINT fk_email_action_results_rule_action FOREIGN KEY (rule_action_id) REFERENCES Rule_actions (id)
) ${table_options};

CREATE TABLE Email_errors (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  phase VARCHAR(20) NOT NULL,
  message TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_errors_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE
) ${table_options};

CREATE TABLE Processing_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trigger_source VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  emails_found INT NOT NULL DEFAULT 0,
  emails_processed INT NOT NULL DEFAULT 0,
  emails_failed INT NOT NULL DEFAULT 0,
  error TEXT NULL
) ${table_options};

CREATE INDEX idx_processing_runs_started_at ON Processing_runs (started_at);
