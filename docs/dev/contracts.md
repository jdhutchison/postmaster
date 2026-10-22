# Contracts between the builder and tester

The builder maintains this file and records each contract **before** implementing the feature it
covers. The tester writes tests from REQUIREMENTS.md, CLARIFICATIONS.md and this file. Either agent
can propose changes to the other; REQUIREMENTS.md and CLARIFICATIONS.md win any disagreement.

Package root: `sh.hutch.postmaster`.

---

## Phase 0: startup

### Command line (task 0.4)

- `sh.hutch.postmaster.Main` is the `@QuarkusMain` class. It also implements `QuarkusApplication`.
- `Main.configLocation(args: Array<out String>): String` returns the absolute `file:` URI of the single argument.
  - It throws `IllegalArgumentException` when there isn't exactly one argument, or when the path is invalid, missing, not a regular file or unreadable. Each message names the problem and ends with the usage line `Usage: postmaster <path to YAML configuration file>`.
- `Main.main(args)`, on bad arguments:
  - prints the message to stderr
  - reports exit code `Main.EXIT_USAGE` (2) through `ApplicationLifecycleManager.getDefaultExitCodeHandler()`. That is `System.exit` in a real run, and recorded by `@QuarkusMainTest`.

  On good arguments, it sets the system property `quarkus.config.locations` to the URI and calls `Quarkus.run`.
- `Main.run(args)` (the `QuarkusApplication`) re-checks the arguments and returns `EXIT_USAGE` on bad ones. Otherwise it waits for exit and returns 0.
- Note: a successful `main()` call sets a JVM-wide system property. A `@QuarkusMainTest` launched with a *valid* path would leak it into later tests in the same JVM.

### Startup configuration (YAML file, prefix `postmaster`)

Mapped by `sh.hutch.postmaster.config.PostmasterConfig` (`@ConfigMapping`). Unknown `postmaster.*` keys fail validation (SRCFG00050), so test-only properties must not use this prefix.

| Key | Required | Default | Meaning |
|---|---|---|---|
| `postmaster.encryption-key` | yes | none | Base64 (standard alphabet, padded) of exactly 32 bytes: the AES-256 key. Anything else fails startup with a message naming the key. |
| `postmaster.khal-config` | yes | none | Path to the khal configuration file |
| `postmaster.khal-command` | no | `khal` | Command used to run khal |
| `postmaster.chromium-command` | no | `chromium` | Command used to run headless Chromium |
| `postmaster.curl-command` | no | `curl` | Command used to run curl |

Other startup keys are standard Quarkus keys: `quarkus.datasource.jdbc.url`, `quarkus.datasource.username` and `quarkus.datasource.password`. See `config/postmaster.example.yml`.

### Main `application.yaml` facts tests rely on

- `quarkus.http.root-path` is `/mail`, so every route below is under `/mail`.
- `quarkus.http.test-port` is not set in main config.
- `quarkus.datasource.db-kind` is `mariadb`, unprofiled. Tests override it with `"%test"`.
- `quarkus.flyway.locations` is `db/migration`, unprofiled and overridable. `quarkus.flyway.migrate-at-start` is true.
- `quarkus.flyway.placeholders.table_options` is the MariaDB table options. **H2 in MariaDB mode accepts them**, so tests don't need to override this (verified with H2 2.4.240).
- Production migrations are `V1`–`V899`. `V900`+ is reserved for the tester's known data sets in `db/testdata`.

---

## Phase 1: data layer

### Tables and stored values

Migrations: `V1__create_schema.sql`, `V2__seed_settings.sql`, `V3__seed_types.sql`, `V4__seed_prompts.sql`. Changes from REQUIREMENTS.md, all to avoid words reserved in MariaDB or H2:

| Requirements | Schema |
|---|---|
| `Settings.key` | `setting_key` |
| `Settings.value` | `setting_value` |
| `Rules.type` | `rule_type` |
| `Rule_actions.type` | `type_id`, plus a new `calendar_id` |
| `Emails.type` | `type_id` |
| `Emails.received` | `received_at` |
| `Email_events.timestamp` | `starts_at` |
| `Email_events.calendar` | `calendar_id` |

Normalised tables (D7) replace the `Emails.attachments`, `classification_rules`, `action_rules` and `errors` columns.

| Column | Stored values |
|---|---|
| `Emails.status` | `pending`, `processed`, `skipped`, `failed` (D1) |
| `Email_errors.phase` | `fetch`, `classification`, `llm`, `action`, `manual` (`manual` is an action run from the UI or MCP) |
| `Email_action_results.source` | `rule`, `ui`, `mcp` |
| `Email_action_results.status` | `done`, `skipped`, `failed` |
| `Rules.rule_type` and `Email_rule_matches.rule_type` | `classification`, `action` |
| `Rule_actions.action_type` | the requirement strings exactly: `set_type`, `move`, `check_for_events`, `download_attachments`, `set_reply_needed`, `fetch_link_contents`, `create_events`, `skip_processing`, `mark_read`, `mark_unread`, `process_bill`, `forward_email`, `delete` |
| `Conditions.attribute` | `subject`, `sender`, `message`, `events`, `attachments`, `bill_url`, `type`, `link` |
| `Conditions.match_type` | `is`, `contains`, `starts_with`, `regex`, `has`, `is_present` |
| `Conditions.test` | NULL for `has` and `is_present` (Q10), otherwise the text to match |
| `Processing_runs.trigger_source` | `schedule`, `manual` |
| `Processing_runs.status` | `running`, `succeeded`, `failed` |
| `Settings.value_type` | `text`, `int`, `bool`, `duration`, `encrypted`, `path`, `list`, `choice`, `timezone`, `url` |
| `Settings.setting_value` for `list` | items joined with comma plus space (`pdf, ics`), normalised on update |
| `Settings.setting_value` for `encrypted` | `enc:` + base64(12-byte IV ‖ AES-GCM ciphertext and 128-bit tag); NULL when not set |
| `Settings.setting_value` for `bool` | `true` / `false` |
| `Settings.allowed_values` | comma separated, for `choice` only |
| `Email_attachments.stored_path` | **relative** to the `attachments.path` setting, e.g. `2026/10/42/bill.pdf`; NULL until downloaded |
| `Email_events.starts_at` and `ends_at` | **local wall-clock** times in the `processing.timezone` setting (not UTC). All-day events have `all_day = TRUE`, `starts_at` at 00:00 and `ends_at` NULL. |
| other `*_at` columns | UTC instants (`quarkus.hibernate-orm.jdbc.timezone=UTC`) |
| `Emails.bill_due_date` | `DATE` |
| `Emails.bill_amount` | `DECIMAL(12,2)` |
| `Types.system_key` | `bill` for the Bill type; NULL for every other seeded type |
| `Prompts.prompt_key` | NULL for user-created prompts |
| `Emails.message_id` | the Message-ID header without angle brackets. Ids longer than 500 characters are stored as `sha256:<hex>`. A message with no Message-ID gets `generated:<sha256 hex of date, from, subject and uidvalidity/uid>`. |

Conditions on attributes:
- `type` compares against the email's type **name**, case-insensitively for `is`.
- `bill_url` is `Emails.bill_download_url`.
- `events` with `regex` matches any event title.
- `attachments` with `regex` matches any filename.

Soft deletes use `deleted = TRUE` with `deleted_at` set:
- Types, Calendars, Prompts, Rules, Rule_actions and Emails are soft-deleted.
- Conditions are deleted for real when removed from a rule. They have no `deleted` column, following the requirements' schema.

### Seed contract

**Types** (`V3`), in this order. Names are exactly as in REQUIREMENTS.md, with trailing spaces trimmed.

| name | description | system_key |
|---|---|---|
| Bill | A utility bill | `bill` |
| Primary school | Message from Brunswick South | |
| High school | Message from Princes Hill SC | |
| Statement notification | Statement available for card or bank | |
| Legal | *(NULL)* | |
| Caloundra West | Message about property | |
| Ebay | Ebay business | |
| Payslip | Payslips | |
| Showfit | Message about Jeremy's dancing | |

Each type has a colour, `#rrggbb` lower-case hex.

**Prompts** (`V4`). Placeholders are `{{calendars}}`, `{{events}}`, `{{subject}}`, `{{sender}}`, `{{text}}`, `{{html}}`, `{{page}}` and `{{types}}`.

| prompt_key | name | placeholders used |
|---|---|---|
| `summarise` | Summarising the email | sender, subject, text |
| `classify` | Classifying and summarising email | types, sender, subject, text |
| `select_calendar` | Selecting a calendar | calendars, events |
| `summarise_page` | Summarising contents of fetched page | page |
| `bill_details` | Checking for the bill details | sender, subject, html |
| `identify_events` | Identifying events | sender, subject, text |

The `summarise` prompt asks for the `action_required` and `action` JSON fields (R1).

**Settings** (`V2`): 28 rows, `position` in this order.

| setting_key | value_type | default | allowed_values |
|---|---|---|---|
| `processing.frequency` | duration | `1h` | |
| `processing.lookback` | duration | `7d` | |
| `processing.timezone` | timezone | `Australia/Melbourne` | |
| `imap.host` | text | `mail-dovecot` | |
| `imap.port` | int | `143` | |
| `imap.security` | choice | `none` | `none,starttls,tls` |
| `imap.username` | text | NULL | |
| `imap.password` | encrypted | NULL | |
| `imap.inbox_folder` | text | `INBOX` | |
| `imap.trash_folder` | text | `Trash` | |
| `smtp.host` | text | `mail-opensmtp` | |
| `smtp.port` | int | `25` | |
| `smtp.security` | choice | `none` | `none,starttls,tls` |
| `smtp.username` | text | NULL | |
| `smtp.password` | encrypted | NULL | |
| `smtp.from_address` | text | NULL | |
| `llm.url` | url | `http://kodiakbear:9931/v1` | |
| `llm.api_key` | encrypted | NULL | |
| `llm.model` | text | `qwen3.8-9b-100k` | |
| `llm.timeout` | duration | `2m` | |
| `llm.max_input_chars` | int | `60000` | |
| `attachments.path` | path | `/data/attachments` | |
| `attachments.extensions` | list | `pdf` | |
| `events.default_duration` | duration | `1h` | |
| `fetch.user_agent` | text | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36` | |
| `fetch.timeout` | duration | `30s` | |
| `bills.pay_link_text` | list | `Pay now, Pay your bill, Pay bill, Make a payment, Pay online` | |
| `bills.bill_link_text` | list | `View bill, View your bill, Download bill, Download your bill, View statement, Download PDF` | |

The keys are constants in `sh.hutch.postmaster.services.SettingKeys`.

### Enums (`sh.hutch.postmaster.domain`)

Each enum has `code: String` (the stored value) and `companion fun fromCode(code: String)`, which throws `IllegalArgumentException` for unknown codes. Entities use them through JPA converters.

| Enum | Values |
|---|---|
| `RuleType` | CLASSIFICATION, ACTION |
| `ConditionAttribute` | SUBJECT, SENDER, MESSAGE, EVENTS, ATTACHMENTS, BILL_URL, TYPE, LINK |
| `MatchType` | IS, CONTAINS, STARTS_WITH, REGEX, HAS, IS_PRESENT |
| `EmailStatus` | PENDING, PROCESSED, SKIPPED, FAILED |
| `ErrorPhase` | FETCH, CLASSIFICATION, LLM, ACTION, MANUAL |
| `ActionSource` | RULE, UI, MCP |
| `ActionResultStatus` | DONE, SKIPPED, FAILED |
| `RunTrigger` | SCHEDULE, MANUAL |
| `RunStatus` | RUNNING, SUCCEEDED, FAILED |
| `SettingType` | TEXT, INT, BOOL, DURATION, ENCRYPTED, PATH, LIST, CHOICE, TIMEZONE, URL |

- Action types are stored as plain strings, so new action types can be registered without a migration. The built-in names are constants in `sh.hutch.postmaster.domain.ActionTypes`, listed in `ActionTypes.BUILT_IN`.
- The condition compatibility matrix (Q9) is `MatchType.appliesTo(attribute)`, with `MatchType.needsTest` (false for HAS and IS_PRESENT).

### Entities (`sh.hutch.postmaster.entities`)

Property names are the camelCase form of the columns.

| Entity | Table |
|---|---|
| `SettingEntity` | Settings |
| `TypeEntity` | Types |
| `CalendarEntity` | Calendars |
| `PromptEntity` | Prompts |
| `RuleEntity` | Rules. `conditions: MutableList<ConditionEntity>` and `actions: MutableList<RuleActionEntity>` are ordered by `position` and cascade. |
| `ConditionEntity` | Conditions |
| `RuleActionEntity` | Rule_actions. `type: TypeEntity?` and `calendar: CalendarEntity?`. |
| `EmailEntity` | Emails. `type: TypeEntity?`, plus collections `events`, `attachments`, `ruleMatches`, `actionResults`, `errors`. |
| `EmailEventEntity` | Email_events |
| `EmailAttachmentEntity` | Email_attachments |
| `EmailRuleMatchEntity` | Email_rule_matches |
| `EmailActionResultEntity` | Email_action_results |
| `EmailErrorEntity` | Email_errors |
| `ProcessingRunEntity` | Processing_runs |

- Soft-deletable entities implement `SoftDeletable` (`deleted`, `deletedAt`, `markDeleted(now: Instant = Instant.now())`).
- `createdAt` and `lastUpdatedAt` are set automatically on insert and update.

### Repositories (`sh.hutch.postmaster.repositories`, Panache Kotlin `PanacheRepository<…Entity>`)

"Active" means not soft-deleted.

**`SettingRepository`**
- `findByKey(key): SettingEntity?`
- `listOrdered()`, by position

**`TypeRepository`**
- `listActive()`, by name, case-insensitive
- `findActive(id)`
- `findActiveByName(name)`, case-insensitive
- `findBySystemKey(key)`
- `findBill()`

**`CalendarRepository`**
- `listActive()`, by name
- `listEnabled()`: active and enabled
- `findActive(id)`
- `findActiveByName(name)`

**`PromptRepository`**
- `listActive()`, by name
- `findActive(id)`
- `findByKey(promptKey)`, active only
- `findActiveByName(name)`

**`RuleRepository`**
- `listActive(ruleType)`: priority descending, then id ascending
- `listEnabled(ruleType)`: active and enabled, same order
- `findActive(id)`
- `findActiveByName(ruleType, name)`, case-insensitive

**`ConditionRepository`**
- `listForRule(ruleId)`, by position

**`RuleActionRepository`**
- `listActiveForRule(ruleId)`, by position
- `listEnabledForRule(ruleId)`

**`EmailRepository`**
- `findByMessageId(messageId)`
- `existsByMessageId(messageId)`: includes soft-deleted emails, so deleted mail isn't reprocessed
- `findActive(id)`
- `listRecent(limit)`: active, by `received_at` descending

**Per-email child tables**
- `EmailEventRepository`, `EmailAttachmentRepository`, `EmailRuleMatchRepository`, `EmailActionResultRepository` and `EmailErrorRepository` each have `listForEmail(emailId)`.
- `EmailErrorRepository` also has `countForEmail(emailId)`.

**`ProcessingRunRepository`**
- `findLatest()`
- `findRunning()`

### Services and utilities

**`sh.hutch.postmaster.services.Crypto`** (`@ApplicationScoped`)
- `encrypt(plainText: String): String` returns `enc:…`. Each call uses a fresh random IV, so encrypting the same text twice gives different values.
- `decrypt(value: String): String` takes an `enc:`-prefixed value. It throws `CryptoException` (an `IllegalArgumentException`) if the prefix is missing, the base64 is bad, the value was tampered with, or it was encrypted with a different key.
- `isEncrypted(value: String?): Boolean` is true when the value starts with `enc:`.
- `Crypto.PREFIX` is `enc:`.
- `Crypto.decodeKey(base64: String): ByteArray` throws `IllegalArgumentException` unless the input is valid base64 of exactly 32 bytes.

**`sh.hutch.postmaster.util.IsoDurations`** (object)
- `parse(text): Duration` throws `InvalidDurationException` (an `IllegalArgumentException`).
  - It accepts full ISO 8601 `P[nW][nD][T[nH][nM][n[.n]S]]`, plus the simplified form without `P`/`T`: `[nW][nD][nH][nM][nS]`. The simplified form uses integers only, and `M` means **minutes**.
  - Both forms are case-insensitive and need at least one component. Surrounding whitespace is not allowed.
  - Years and months are rejected, because they aren't fixed lengths.
  - Examples: `PT1H`, `1H`, `1h`, `P1D`, `1d`, `4h`, `30m`, `1h30m`, `P1W`, `2w`, `PT90S`, `PT0.5S`.
- `isValid(text): Boolean`
- `parseList(text): List<Duration>` takes a **comma plus space** separated list (`4h, 1d`). It throws `InvalidDurationException` for any other separator (`4h,1d` or `4h ,1d`), empty items, or leading or trailing spaces.
- `isValidList(text): Boolean`
- `format(duration): String` gives the simplified form, e.g. `1d2h`, `30m`, `0s`.

**`sh.hutch.postmaster.services.SettingsService`** (`@ApplicationScoped`)
- `list(): List<Setting>`, by position
- `get(key): Setting` throws `UnknownSettingException` (a `NoSuchElementException`).
- Typed getters return the plain-text value, decrypting encrypted settings. They throw `UnknownSettingException`, or `IllegalStateException` if a stored value doesn't parse.
  - `getString(key): String?`
  - `getInt(key): Int`
  - `getBool(key): Boolean`
  - `getDuration(key): Duration`
  - `getList(key): List<String>`
  - `getZoneId(key): ZoneId`
- `update(key, value: String): Setting` validates by type, then stores the value, trimmed. It throws `SettingValidationException(key, message)` (an `IllegalArgumentException`). By type:

  | Type | Rule |
  |---|---|
  | `text` | Any text. Blank stores NULL. |
  | `int` | A positive integer. Keys ending `.port` must be 1–65535. |
  | `bool` | `true` or `false`, case-insensitive, stored lower case. |
  | `duration` | `IsoDurations` valid and greater than zero. `processing.frequency` must be at least 1 minute. |
  | `encrypted` | Non-blank values are encrypted and stored. **Blank leaves the setting unchanged** (Q17: the masked field starts empty). |
  | `path` | An absolute path. |
  | `list` | Comma separated, with blank items rejected. Stored normalised as `a, b`. At least one item. |
  | `choice` | One of `allowed_values`, case-insensitive, stored as the allowed spelling. |
  | `timezone` | Valid for `ZoneId.of`. |
  | `url` | `http` or `https` with a host. |

  Blank values for any type except `text` and `encrypted` are rejected with "A value is required".
- `clear(key): Setting` sets the value to NULL. Only allowed for `text` and `encrypted` settings; any other type throws `SettingValidationException`.
- `SettingsService.MASK` is `••••••••`.

**`sh.hutch.postmaster.models.Setting`** (data class)
- `key`, `label`, `type: SettingType`
- `allowedValues: List<String>`, `description: String?`
- `value: String?` is the stored value, but always **null** for encrypted settings, so plain text never leaves the service.
- `displayValue: String` is `MASK` for an encrypted setting that is set, `""` for unset settings, and otherwise the stored value.
- `hasValue: Boolean`, `position: Int`

**Mappers** (`sh.hutch.postmaster.mappers`, MapStruct with the `jakarta-cdi` component model)
- `SettingMapper.toModel(entity: SettingEntity): Setting`
