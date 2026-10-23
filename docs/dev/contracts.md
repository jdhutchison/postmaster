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
- `quarkus.flyway.placeholders.table_options` is `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`, so the collation is the database default. **H2 in MariaDB mode accepts it**, so tests don't need to override this (verified with H2 2.4.240).
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

Portability decisions (D20):
- **Identifiers.** No table or column name is a reserved word in MariaDB or H2 2.x, and no identifier is quoted anywhere, in migrations or in mappings. Table names in `@Table` and `@JoinTable` match the migrations exactly, because MariaDB on Linux is case-sensitive about table names (`lower_case_table_names=0`).
- **Case-insensitive unique names.** Types, Calendars and Rules have a `name_key` column:
  - It holds `name.trim().lowercase(Locale.ROOT)` while the row is active and NULL once soft-deleted. The entity sets it on insert and update.
  - The column uses `COLLATE utf8mb4_bin`, so its unique constraints behave the same on MariaDB and H2. The constraints are `uq_types_name_key`, `uq_calendars_name_key`, and for rules (`rule_type`, `name_key`) `uq_rules_type_name_key`.
  - A name that clashes only with a soft-deleted row is **allowed**.
  - The services check first and throw `DuplicateNameException` (Phase 3). The constraint is the backstop.
- **Times.** All times are `DATETIME(6)`, never `TIMESTAMP`. Every `*_at` column holds UTC, except `Email_events.starts_at`/`ends_at`.
- **Index sizes.** Unique and indexed `VARCHAR`s are at most 500 characters (2000 bytes in utf8mb4), within MariaDB's 3072-byte index key limit. `Emails.message_id` is `VARCHAR(998)` and isn't indexed. Its uniqueness comes from `message_hash`: `CHAR(64)`, unique, the lower-case hex SHA-256 of the stored `message_id`.
- **Verification.** Verified on H2 2.4.240 (with and without `NON_KEYWORDS=VALUE`) and on MariaDB 13.0.2, where the default collation is `utf8mb4_uca1400_ai_ci`.

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
| `Emails.message_id` | the normalised Message-ID (see `MessageIds` below), up to 998 characters |
| `Emails.message_hash` | `MessageIds.hash(messageId)`: lower-case hex SHA-256 of the UTF-8 bytes of `message_id`. Unique. |
| `Types.name_key`, `Calendars.name_key`, `Rules.name_key` | lower-cased name while active, NULL when soft-deleted |

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

### Enums (`sh.hutch.postmaster.models`)

Package layout (CLARIFICATIONS.md Q22):
- The enums, `CodedEnum`, `codedEnumOf` and `ActionTypes` are in `sh.hutch.postmaster.models`, which has no JPA dependency.
- The JPA converters (`CodedEnumConverter` and one `…Converter` per enum, e.g. `RuleTypeConverter`) are in `sh.hutch.postmaster.entities`.

Each enum implements `CodedEnum`, with `code: String` (the stored value) and `companion fun fromCode(code: String)`, which throws `IllegalArgumentException` for unknown codes.

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

- Action types are stored as plain strings, so new action types can be registered without a migration. The built-in names are constants in `sh.hutch.postmaster.models.ActionTypes`, listed in `ActionTypes.BUILT_IN`.
- The condition compatibility matrix (Q9) is `MatchType.appliesTo(attribute)`, with `MatchType.needsTest` (false for HAS and IS_PRESENT).
- **A rule matches only when ALL its conditions match (AND, CLARIFICATIONS.md Q21).** The rule engine arrives in Phase 5.1.

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

- Soft-deletable entities implement `SoftDeletable` (`deleted`, `deletedAt`, `markDeleted(now: Instant = Times.now())`). `markDeleted` on `TypeEntity`, `CalendarEntity` and `RuleEntity` also sets `nameKey` to NULL.
- `createdAt` and `lastUpdatedAt` are set automatically on insert and update, in `AuditedEntity`. `id` is in `BaseEntity`.
- **Time binding.** `Instant` fields are mapped with `@JdbcTypeCode(SqlTypes.TIMESTAMP)` and the UTC JDBC zone, so columns hold UTC wall time on both databases whatever the JVM zone. `Email_events` `startsAt`/`endsAt` (`LocalDateTime`) and `Emails.billDueDate` (`LocalDate`) use `SqlTypes.LOCAL_DATE_TIME`/`LOCAL_DATE`, bound with `setObject`, so no zone shift. Timestamps are truncated to microseconds (`sh.hutch.postmaster.util.Times.now()`).
- `TypeEntity.BILL_KEY` is `"bill"`. `TypeEntity` has `isSystem` and `isBill`; `PromptEntity` has `isSystem`.
- **`EmailEntity` enforces Q13.** `type`, `actionRequired` and `actionDescription` have protected setters. Change them only through these methods:
  - `assignType(type)`: setting a Bill type clears the action flag
  - `flagActionRequired(required, description)`: ignored for bills, and `description` is kept only when `required`
  - `clearActionRequired()`
- `EmailEntity.messageHash` is recomputed from `messageId` on insert and update.

### Repositories (`sh.hutch.postmaster.repositories`, Panache Kotlin `PanacheRepository<…Entity>`)

"Active" means not soft-deleted.

**`SettingRepository`**
- `findByKey(key): SettingEntity?`
- `listOrdered()`, by position

**`TypeRepository`**
- `listActive()`, by name, case-insensitive
- `findActive(id)`
- `findActiveByName(name)`, case-insensitive (compares `name_key`)
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
- `findByMessageId(messageId)` looks up by `MessageIds.hash(messageId)`. `messageId` must already be normalised.
- `existsByMessageId(messageId)`: same lookup, and includes soft-deleted emails, so deleted mail isn't reprocessed
- `findActive(id)`
- `listRecent(limit)`: active, by `received_at` descending

**Per-email child tables**
- `EmailEventRepository`, `EmailAttachmentRepository`, `EmailRuleMatchRepository`, `EmailActionResultRepository` and `EmailErrorRepository` each have `listForEmail(emailId)`.
- `EmailErrorRepository` also has `countForEmail(emailId)`.

**`ProcessingRunRepository`**
- `findLatest()`
- `findRunning()`

### Services and utilities

**`sh.hutch.postmaster.services.MessageIds`** (object)
- `normalise(raw: String?): String?`
  - Removes all whitespace, including from folded headers, then a single pair of surrounding angle brackets `<…>`.
  - Returns null if the result is blank.
  - Values longer than 998 characters are cut to 998.
  - Case is kept.
- `generated(receivedAt: Instant?, from: String?, subject: String?, uidValidity: Long?, uid: Long?): String` returns `generated:` + the lower-case hex SHA-256 of those values joined with `\n`, with nulls as empty strings. It is used when a message has no Message-ID.
- `hash(messageId: String): String` is the lower-case hex SHA-256 of the UTF-8 bytes (64 characters).

**Name uniqueness (Phase 3, for D20).**
- `TypeService`, `CalendarService` and `RuleService` create and rename methods throw `sh.hutch.postmaster.services.DuplicateNameException(field: String, message: String)`, an `IllegalArgumentException`.
  - `field` is `"name"`.
  - It is thrown when another *active* row has the same name ignoring case. For rules, only rows of the same rule type count.
- Exact signatures will be recorded here before Phase 3 starts.
- In Phase 1 the repositories' `findActiveByName` already compares by `name_key`.

**`sh.hutch.postmaster.services.Crypto`** (`@ApplicationScoped`)
- `encrypt(plainText: String): String` returns `enc:…`. Each call uses a fresh random IV, so encrypting the same text twice gives different values.
- `decrypt(value: String): String` takes an `enc:`-prefixed value. It throws `CryptoException` (an `IllegalArgumentException`) if the prefix is missing, the base64 is bad, the value was tampered with, or it was encrypted with a different key.
- `isEncrypted(value: String?): Boolean` is true when the value starts with `enc:`.
- `Crypto.PREFIX` is `enc:`.
- `Crypto.decodeKey(base64: String): ByteArray` throws `IllegalArgumentException` unless the input is valid base64 of exactly 32 bytes (`Crypto.KEY_BYTES`).
- Startup (`sh.hutch.postmaster.config.StartupChecks`) fails with that message if `postmaster.encryption-key` is invalid. If `postmaster.khal-config` is missing or unreadable, it only logs a warning.

**`sh.hutch.postmaster.util.IsoDurations`** (object)
- `parse(text): Duration` throws `InvalidDurationException` (an `IllegalArgumentException`).
  - It accepts full ISO 8601 `P[nW][nD][T[nH][nM][n[.n]S]]`, plus the simplified form without `P`/`T`: `[nW][nD][nH][nM][nS]`. The simplified form uses integers only, and `M` means **minutes**.
  - Both forms are case-insensitive and need at least one component. Surrounding whitespace is not allowed.
  - Years and months are rejected, because they aren't fixed lengths.
  - Examples: `PT1H`, `1H`, `1h`, `P1D`, `1d`, `4h`, `30m`, `1h30m`, `P1W`, `2w`, `PT90S`, `PT0.5S`.
- `isValid(text): Boolean`
- `parseList(text): List<Duration>` takes a **comma plus space** separated list (`4h, 1d`). It throws `InvalidDurationException` for any other separator (`4h,1d` or `4h ,1d`), empty items, or leading or trailing spaces.
- `isValidList(text): Boolean`
- `format(duration): String` gives the simplified form, e.g. `1d2h`, `30m`, `0s`. Weeks come out as days, and durations with fractions of a second use ISO 8601 (`PT1.5S`).
- `IsoDurations.LIST_SEPARATOR` is `", "`.

**`sh.hutch.postmaster.services.SettingsService`** (`@ApplicationScoped`)
- `list(): List<Setting>`, by position
- `get(key): Setting` throws `UnknownSettingException` (a `NoSuchElementException`).
- Typed getters return the plain-text value, decrypting encrypted settings. They throw `UnknownSettingException`, or `IllegalStateException` if a stored value doesn't parse.
  - `getString(key): String?` throws `IllegalStateException` if an encrypted value can't be decrypted, e.g. the key changed.
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
  | `encrypted` | Non-blank values are encrypted and stored **as entered, not trimmed**. **Blank leaves the setting unchanged** (Q17: the masked field starts empty). |
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

---

## Phase 2: UI foundation

All routes below are under `/mail`. Pages are server-rendered Qute templates enhanced with HTMX 2.0.11 and one small script, `postmaster.js`. Every asset is served by the app, with no CDN or other external request. A `Content-Security-Policy` header (`default-src 'self'`, `script-src 'self'`, `style-src 'self' 'unsafe-inline'`) enforces this.

### Assets (`src/main/resources/META-INF/resources/assets/`)

| URL | What |
|---|---|
| `/mail/assets/vendor/htmx/htmx.min.js` | HTMX **2.0.11** (npm `htmx.org`, 0BSD, `LICENSE` beside it) |
| `/mail/assets/fonts/atkinson-hyperlegible-next/*.woff2` | Atkinson Hyperlegible Next variable font, weights 200–800. From npm `@fontsource-variable/atkinson-hyperlegible-next` **5.3.0**, under the SIL OFL 1.1, with `OFL.txt` beside it. Files: `atkinson-hyperlegible-next-latin-wght-normal.woff2`, `…-latin-ext-wght-normal.woff2`, `…-latin-wght-italic.woff2`, `…-latin-ext-wght-italic.woff2`. |
| `/mail/assets/css/postmaster.css` | All styles |
| `/mail/assets/js/postmaster.js` | All behaviour; exposes `window.Postmaster` |
| `/mail/assets/VENDOR.md` | Versions, sources, SHA-256 and licences of the vendored files |

- The font family is `"Atkinson Hyperlegible Next"`, with a `system-ui, sans-serif` fallback. `@font-face` is in `postmaster.css`.
- The layout links CSS and JS with `?v=<application version>` for cache busting.

### Palette (CSS custom properties on `:root`)

Values are exactly those of the official specification, https://draculatheme.com/spec ("Color Palette" Dracula Classic and Alucard Classic, plus "UI Color Palette"). Light mode (Alucard) is the default; `@media (prefers-color-scheme: dark)` switches to Dracula.

| Property | Alucard (light) | Dracula (dark) | Spec token |
|---|---|---|---|
| `--pm-background` | `#FFFBEB` | `#282A36` | Background |
| `--pm-current-line` | `#6C664B` | `#6272A4` | Current Line |
| `--pm-selection` | `#CFCFDE` | `#44475A` | Selection |
| `--pm-foreground` | `#1F1F1F` | `#F8F8F2` | Foreground |
| `--pm-comment` | `#6C664B` | `#6272A4` | Comment |
| `--pm-red` | `#CB3A2A` | `#FF5555` | Red |
| `--pm-orange` | `#A34D14` | `#FFB86C` | Orange |
| `--pm-yellow` | `#846E15` | `#F1FA8C` | Yellow |
| `--pm-green` | `#14710A` | `#50FA7B` | Green |
| `--pm-cyan` | `#036A96` | `#8BE9FD` | Cyan |
| `--pm-purple` | `#644AC9` | `#BD93F9` | Purple |
| `--pm-pink` | `#A3144D` | `#FF79C6` | Pink |
| `--pm-bg-floating` | `#EFEDDC` | `#343746` | UI: Floating interactive elements |
| `--pm-bg-lighter` | `#ECE9DF` | `#424450` | UI: Background Lighter |
| `--pm-bg-light` | `#DEDCCF` | `#343746` | UI: Background Light |
| `--pm-bg-dark` | `#CECCC0` | `#21222C` | UI: Background Dark |
| `--pm-bg-darker` | `#BCBAB3` | `#191A21` | UI: Background Darker |
| `--pm-focus` | `#815CD6` | `#815CD6` | Functional Purple (focus indicators) |

Element colours:

| Element | Colours |
|---|---|
| `body` | background `--pm-background`, text `--pm-foreground` |
| links | `--pm-cyan` |
| muted text | `--pm-comment` |
| table header cells | background `--pm-bg-light` |
| table borders | `--pm-selection` |
| hovered rows | `--pm-bg-lighter` |
| text selection (`::selection`) | `--pm-selection` |
| menu button and menu panel | `--pm-bg-floating` (frosted, see below) |
| success toasts | left border `--pm-green` |
| error toasts | left border `--pm-red` |
| primary buttons (`.pm-button--primary`) | background `--pm-purple`, text `--pm-background` |
| danger buttons (`.pm-button--danger`) | background `--pm-red`, text `--pm-background` |
| focus outlines | `--pm-focus` |

`color-scheme` is `light` or `dark` to match.

### Layout and templates

Page templates use the base layout `templates/layout.html` through an include with sections:

```
{#include layout page="types"}
  {#title}Types{/title}
  {#content}
    …page content…
  {/content}
{/include}
```

- `page` is optional. It's the key of the menu item to mark `aria-current="page"`: `dashboard`, `emails`, `types`, `calendars`, `prompts`, `rules-classification`, `rules-action` or `configuration`.
- The `title` section sets both `<title>` (as `<title> · Postmaster`) and the page's `<h1 class="pm-page-title" data-testid="page-title">`.
- A resource returns a `TemplateInstance`. The layout needs no data from it; it uses the template globals and the `inject:` beans below.
- Templates and tags on the test classpath (`src/test/resources/templates/…`) are found too, so a test fixture page can `{#include layout}` and use every tag.

Structure rendered by the layout:

```html
<body class="pm-body" data-page="{page}">
  <a class="pm-skip-link" href="#pm-main">Skip to content</a>
  <button id="pm-menu-button" class="pm-menu-button" data-testid="menu-button" …>
  <nav id="pm-menu" class="pm-menu" data-testid="menu" hidden>…</nav>
  <main id="pm-main" class="pm-main" data-testid="main">
    <h1 class="pm-page-title" data-testid="page-title">{title}</h1>
    {content}
  </main>
  <div id="pm-toasts" class="pm-toasts" data-testid="toasts">…</div>
  <dialog id="pm-confirm" data-testid="confirm-dialog">…</dialog>
</body>
```

**Template globals:**
- `{base}` is the root path without a trailing slash (`/mail`). Write links as `{base}/types`.
- `{appVersion}` is the application version.

**Placeholder page:** `GET /mail/` renders `templates/HomeController/home.html` (`data-testid="home-page"`) in the layout with `page="dashboard"`. The dashboard (6.3) replaces it.

### Menu

- **Button.** `button#pm-menu-button.pm-menu-button` (`data-testid="menu-button"`) with `aria-label="Menu"`, `aria-controls="pm-menu"` and `aria-expanded="false|true"`.
  - It's `position: fixed` at the top left (`top` and `left` 0.75rem), with `z-index: 100`, so it floats over the content.
  - The frosted glass is `backdrop-filter: blur(12px) saturate(140%)` (plus `-webkit-backdrop-filter`) over a translucent `--pm-bg-floating`.
- **Panel.** `nav#pm-menu.pm-menu` (`data-testid="menu"`, `aria-label="Main"`) is hidden with the `hidden` attribute when closed. It is also fixed and frosted, below the button.
- **Items**, in order (each is an `a` in `li`):

  | Label | href | data-testid |
  |---|---|---|
  | Dashboard | `/mail/` | `menu-dashboard` |
  | Emails | `/mail/emails` | `menu-emails` |
  | Types | `/mail/types` | `menu-types` |
  | Calendars | `/mail/calendars` | `menu-calendars` |
  | Prompts | `/mail/prompts` | `menu-prompts` |
  | Classification rules | `/mail/rules/classification` | `menu-rules-classification` |
  | Action rules | `/mail/rules/action` | `menu-rules-action` |
  | Configuration | `/mail/configuration` | `menu-configuration` |

  These are the routes later phases implement.
- **Behaviour:**
  - Click, Enter or Space on the button toggles the panel, keeping `aria-expanded` in step.
  - Opening moves focus to the first item. Up and Down arrows move between items, and Home and End jump to the first and last.
  - **Escape** closes the panel and returns focus to the button.
  - A click outside the button and panel closes it.
  - Moving focus out of the panel (Tab past the last item) closes it.

### Toasts

- **Container:** `div#pm-toasts.pm-toasts` (`data-testid="toasts"`), fixed at the bottom right (full width minus gutters on phones).
- **A toast:**

  ```html
  <div class="pm-toast pm-toast--success" data-testid="toast" data-level="success" role="status">
    <span class="pm-toast__message" data-testid="toast-message">Saved</span>
    <button type="button" class="pm-toast__close" data-testid="toast-close" aria-label="Dismiss">×</button>
  </div>
  ```

  - Levels are `success`, `info` and `error` (`pm-toast--error`, `role="alert"`).
  - **success and info:** fading starts after **4000 ms** (class `pm-toast--leaving`, a 300 ms opacity transition), and the toast is removed from the DOM by **4500 ms**. While the pointer is over the toast, removal waits until it leaves.
  - **error:** never removed automatically. Only the close button removes it.
  - The close button removes any toast at once.
- **While a modal dialog is open**, toasts are shown inside it, so they appear above its backdrop and stay clickable. The dialog's modality makes the rest of the page inert. A `div.pm-toasts[data-testid="dialog-toasts"]` is appended to the dialog, and toasts already on the page move into it. When the dialog closes, remaining toasts move back to `#pm-toasts`. Selectors on `[data-testid=toast]` therefore work in both places.
- **Raising toasts:**
  1. **Full page load.**
     - Toasts added to the request-scoped bean `sh.hutch.postmaster.controllers.PageToasts` (`@Named("pageToasts")`, methods `success(message)`, `info(message)` and `error(message)`) are rendered by the layout as toast elements.
     - So are **flash** toasts from the cookie `pm_flash`. To redirect with toasts, use `Flash.redirect(location: String, vararg toasts: Toast): Response`. It returns `303 See Other` with a **path-only** `Location` header (`/types` becomes `/mail/types`; a location already under `/mail` is kept as is) and sets `pm_flash`: base64url JSON, `Path=/mail`, `HttpOnly`, `SameSite=Lax`, `Max-Age=60`.
     - The next full-page (non-HTMX) HTML response renders the toasts and clears the cookie.
  2. **HTMX response.**
     - The header is `HX-Trigger: {"pm-toast":{"toasts":[{"level":"success","message":"Saved"}]}}`. Build it with `Toasts.hxTrigger(vararg toasts: Toast): String`, or `Toasts.hxTrigger(toasts: List<Toast>, events: Map<String, Any?>)` to add other events. Non-ASCII characters are escaped as `\uXXXX` so the header stays ASCII.
     - `Toasts.HEADER` is `"HX-Trigger"`.
     - `postmaster.js` listens for the `pm-toast` event on `document.body`.
  3. **Failed HTMX requests.** With an error status and no `pm-toast` trigger, the toast says `Request failed (<status>)`. When the request can't reach the server, it says `Network error: the server could not be reached`. Both are error toasts.
  4. **JavaScript:** `Postmaster.toast(level, message)`.
- **Kotlin types** (`sh.hutch.postmaster.controllers`):
  - `ToastLevel` (SUCCESS/INFO/ERROR, `code` = `success`/`info`/`error`)
  - `data class Toast(level: ToastLevel, message: String)`, with `Toast.success(m)`, `Toast.info(m)` and `Toast.error(m)`

### Dialogs (`<dialog>`)

- **Modal tag:**

  ```
  {#modal id="type-dialog" title="Edit type"}…body…{/modal}
  ```

  It renders:

  ```html
  <dialog id="type-dialog" class="pm-dialog" data-testid="type-dialog" aria-labelledby="type-dialog-title">
    <div class="pm-dialog__header">
      <h2 id="type-dialog-title" class="pm-dialog__title">Edit type</h2>
      <button type="button" class="pm-dialog__close" data-pm-close aria-label="Close" data-testid="dialog-close">×</button>
    </div>
    <div class="pm-dialog__body" id="type-dialog-body">…body…</div>
  </dialog>
  ```

  - The optional `testid=` parameter overrides `data-testid` (default: the id).
  - Footer buttons go in the body inside `<div class="pm-dialog__actions">`.
- **Opening a dialog:**
  - a click on any element with `data-pm-open="<dialog id>"`
  - `Postmaster.openDialog(id)`
  - an HTMX response header `HX-Trigger-After-Swap: {"pm-dialog-open":{"id":"<dialog id>"}}`. Typical use: `hx-get` loads the form into `#<id>-body`, then the trigger opens the dialog. HTMX dispatches the event on the requesting element, so that element must still be in the page after the swap.

  It uses `showModal()`.
- **Closing a dialog:**
  - a click on any `[data-pm-close]` inside it
  - `Postmaster.closeDialog(id)`
  - `HX-Trigger: {"pm-dialog-close":{"id":"…"}}`
  - Escape (native)
  - a click on the backdrop, i.e. on the `dialog` element itself, outside its content
- **Focus.** On open, focus goes to the first element with `autofocus`, else the first focusable element in `.pm-dialog__body`, else the close button. On close, it goes back to the element that opened the dialog.
- **Confirm.** One shared confirm dialog in the layout:

  ```html
  <dialog id="pm-confirm" class="pm-dialog pm-dialog--confirm" data-testid="confirm-dialog">
    <p class="pm-confirm__message" data-testid="confirm-message">…</p>
    <button type="button" data-testid="confirm-cancel">Cancel</button>
    <button type="button" data-testid="confirm-ok">OK</button>
  </dialog>
  ```

  - **HTMX actions:** `hx-confirm="Delete type Bill?"`. The `htmx:confirm` event is intercepted and the request is issued only after **OK**.
  - **Plain links and forms:** `data-pm-confirm="Delete type Bill?"` on a submit button or link. The click is intercepted, and the form is submitted or the link followed only after OK.
  - `data-pm-confirm-label="Delete"` on the triggering element sets the OK button's text; it defaults to `OK`.
  - Focus starts on **Cancel**. Escape, a backdrop click or Cancel all cancel.
  - `Postmaster.confirm(message, okLabel?)` returns a `Promise<boolean>`.

### Table component

Tags in `templates/tags/`:

```
{#table id="types-table" testid="types-table"}
  <thead><tr>
    {#th col="name" label="Name" priority=1 /}
    {#th col="description" label="Description" priority=3 /}
  </tr></thead>
  <tbody>
    <tr data-testid="type-row">
      {#td col="name" priority=1}{type.name}{/td}
      {#td col="description" priority=3 title="optional explicit tooltip"}{type.description}{/td}
    </tr>
  </tbody>
{/table}
```

- **`{#table}`** renders `div.pm-table-wrap[data-testid="<testid>-wrap"]` containing `table.pm-table#<id>[data-pm-table][data-testid="<testid>"]`. `testid` defaults to the id.
- **`{#th}`** renders:

  ```html
  <th scope="col" class="pm-th" data-col="name" data-priority="1" data-testid="th-name">
    <span class="pm-th__label">Name</span>
    <span class="pm-resizer" role="separator" aria-orientation="vertical" tabindex="0" aria-label="Resize Name column" data-testid="resize-name"></span>
  </th>
  ```

  Pass `resizable=false` to leave out the resizer, e.g. for an actions column.
- **`{#td}`** renders `<td data-col="…" data-priority="…"><span class="pm-cell" data-testid="cell-<col>">…</span></td>`.
  - A `title=` parameter adds a fixed tooltip: `title="…"` and `data-pm-title-fixed` on the `span`.
  - Pass `truncate=false` for cells holding controls, such as action icons. That renders `span.pm-cell.pm-cell--wrap`, which doesn't truncate.
- **Truncation.** `.pm-cell` is `display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap`. The table is `table-layout: fixed; width: 100%`.
- **Full text on hover** is the native `title` attribute. On `pointerenter` or `focusin`, if the cell is truncated (`scrollWidth > clientWidth`), `postmaster.js` sets `title` to the cell's trimmed text, and it removes a title it set itself when the cell no longer overflows. Cells with `data-pm-title-fixed` keep the server's title.
- **Priority and breakpoints.** Container queries on `.pm-table-wrap` (`container-type: inline-size`), applying equally to `th` and `td` with that `data-priority`:

  | `data-priority` | Hidden when the wrapper is narrower than |
  |---|---|
  | `1` | never |
  | `2` | 480px |
  | `3` | 640px |
  | `4` | 832px |
  | `5` | 1024px |
- **Sticky header.** `thead th` is `position: sticky; top: 0` against the **page (viewport) scroll**. Neither the wrapper nor the table scrolls. The wrapper has `overflow-x: clip`, which clips without creating a scroll container, so the header sticks to the top of the viewport while any part of the table is in view, and the page never scrolls sideways.
- **Resizing.**
  - Dragging `.pm-resizer` (pointer events) changes the column's width and the next visible column's width by the opposite amount. Widths are percentages of the table, so the total never exceeds the table.
  - The minimum column width is 48px.
  - When the resizer has focus, Left and Right arrows resize by 16px, and Enter or a double-click resets all widths.
  - The last visible column's resizer is hidden (class `pm-resizer--hidden`).
  - Widths persist in `localStorage` under `pm-table-widths:<table id>:<visible column keys>` and are ignored if storage is unavailable. When the set of visible columns changes, the stored widths for the new set apply, or the defaults if there are none.
- **No horizontal page scroll** at any width down to 320px.

### Relative times

- **`sh.hutch.postmaster.util.RelativeTime.format(instant: Instant, now: Instant): String`**, for an elapsed time `d = now - instant`:

  | Elapsed time | Result |
  |---|---|
  | less than 60s, or in the future | `just now` |
  | 60s to 59m59s | `{d / 1 minute}m ago` (rounded down; 60s → `1m ago`, 59m59s → `59m ago`) |
  | 60m to 23h59m59s | `{d / 1 hour}h ago` (60m → `1h ago`, 23h59m → `23h ago`) |
  | 24h or more | `{d / 1 day}d ago` (24h → `1d ago`, `45d ago`, `400d ago`); no weeks, months or years |
- **Absolute time.** The `processing.timezone` setting, pattern `EEE d MMM yyyy, HH:mm z`, `Locale.ENGLISH`, e.g. `Fri 23 Oct 2026, 06:40 AEDT`.
- **Bean `sh.hutch.postmaster.controllers.TimeDisplay`** (`@Named("times")`):
  - `relative(instant): String`
  - `absolute(instant): String`
  - `iso(instant): String`: UTC ISO-8601, e.g. `2026-10-22T19:40:00Z`
  - It caches the timezone for 60 seconds.
- **Tag `{#time value=someInstant /}`** renders:

  ```html
  <time class="pm-time" datetime="2026-10-22T19:40:00Z" title="Fri 23 Oct 2026, 06:40 AEDT" data-pm-relative data-testid="time">14m ago</time>
  ```

  It renders nothing for a null value.
- **Refresh.** `postmaster.js` recomputes every `[data-pm-relative]` every 60 seconds with the same rules, from `datetime`. `Postmaster.relativeTime(date, now)` exposes those rules.

### HTMX setup

- The layout includes `<meta name="htmx-config" content='{"includeIndicatorStyles":false}'>`, because the CSP blocks HTMX's injected styles. `.htmx-indicator` styles are in `postmaster.css`.
- `htmx.onLoad` initialises tables, times and confirm hooks in swapped content.
