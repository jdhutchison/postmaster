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
| `Rules.condition_join` | `all` (AND), `any` (OR) |
| `Rule_actions.action_type` | the requirement strings exactly: `set_type`, `move`, `check_for_events`, `download_attachments`, `set_reply_needed`, `fetch_link_contents`, `create_events`, `skip_processing`, `mark_read`, `mark_unread`, `process_bill`, `forward_email`, `delete`, and `update_todo_list` (R5) |
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

The `summarise` prompt asks for `action_items`, each with `action`, `due` and `notes` (R5; it asked for `action_required` and `action` in R1), and uses `{{received}}`, the received time in `processing.timezone`.

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
- **A rule matches when ALL its conditions match (AND, CLARIFICATIONS.md Q21), or ANY one of them when its `conditionJoin` is `ANY` (OR, R8).** `ConditionJoin` (`all`, `any`) is stored in `Rules.condition_join`, default `all`.

### Entities (`sh.hutch.postmaster.entities`)

Property names are the camelCase form of the columns.

| Entity | Table |
|---|---|
| `SettingEntity` | Settings |
| `TypeEntity` | Types |
| `CalendarEntity` | Calendars |
| `PromptEntity` | Prompts |
| `RuleEntity` | Rules. `conditionJoin: ConditionJoin` (default `ALL`). `conditions: MutableList<ConditionEntity>` and `actions: MutableList<RuleActionEntity>` are ordered by `position` and cascade. |
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
- **`EmailEntity` enforces Q13 and R5.** `type`, `actionRequired` and `actionDescription` have protected setters; the last two follow the action items (`actionItems`, `Email_action_items`) that aren't done. Change them only through these methods:
  - `assignType(type)`: setting a Bill type clears the action flag
  - `addActionItem(description, dueDate?, notes?)`: returns the new item; nothing for bills, or when an item with the same description (ignoring case) exists
  - `completeActionItem(item)`
  - `dropActionItems()`: removes the items that aren't in the to-do list (`todoName` null); used before reprocessing and when the type becomes Bill
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

The Water.css palettes (https://watercss.kognise.dev), which replaced Dracula and Alucard on 2026-10-24 (CLARIFICATIONS.md R4). Light mode is the default; `@media (prefers-color-scheme: dark)` switches to dark. Water.css has one accent colour, its link blue, and no status colours, so red, orange, yellow, green and pink are Postmaster's own, chosen to suit it.

| Property | Light | Dark | From Water.css |
|---|---|---|---|
| `--pm-background` | `#FFFFFF` | `#202B38` | `--background-body` |
| `--pm-current-line` | `#70777F` | `#526980` | |
| `--pm-selection` | `#DBDBDB` | `#526980` | `--border` |
| `--pm-foreground` | `#363636` | `#DBDBDB` | `--text-main` |
| `--pm-comment` | `#70777F` | `#A9B1BA` | `--text-muted` |
| `--pm-red` | `#C62828` | `#FF6B6B` | |
| `--pm-orange` | `#B85C00` | `#FFBE85` | dark: `--code` |
| `--pm-yellow` | `#8A7400` | `#EFDB43` | dark: `--highlight` |
| `--pm-green` | `#2E8B32` | `#6BD06F` | |
| `--pm-cyan` | `#0076D1` | `#41ADFF` | `--links` |
| `--pm-accent` | `#0076D1` | `#41ADFF` | `--links` (primary buttons, select arrows, counts) |
| `--pm-pink` | `#B0279E` | `#D941E2` | dark: `--variable` |
| `--pm-bg-floating` | `#F7F7F7` | `#1A242F` | `--background-alt` |
| `--pm-bg-lighter` | `#F2F2F2` | `#2A3949` | |
| `--pm-bg-light` | `#EFEFEF` | `#161F27` | `--background` |
| `--pm-bg-dark` | `#DBDBDB` | `#0C151C` | dark: `--button-base` |
| `--pm-bg-darker` | `#9B9B9B` | `#040A0F` | `--button-hover` |
| `--pm-focus` | `#0096BF` | `#0096BF` | `--focus` (without its transparency) |

The favicon (`assets/favicon.svg`) is the accent colour, light or dark to match the device.

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
| primary buttons (`.pm-button--primary`) | background `--pm-accent`, text `--pm-background` |
| danger buttons (`.pm-button--danger`) | background `--pm-red`, text `--pm-background` |
| focus outlines | `--pm-focus` |

`color-scheme` is `light` or `dark` to match.

### Layout and templates

Page templates use the base layout `templates/layout.html` through an include with sections:

```
{#include layout page="types"}
  {#kicker}Postmaster · Classification{/kicker}
  {#title}Types{/title}
  {#lede}<p class="pm-lede">…</p>{/lede}
  {#actions}<button class="pm-button pm-button--primary" …>New type</button>{/actions}
  {#content}
    <div class="pm-page">
      <aside class="pm-rail">…context…</aside>
      <div class="pm-pane">…content…</div>
    </div>
  {/content}
{/include}
```

- **Masthead sections:** `kicker` (default "Postmaster"), `lede` and `actions` are optional. The actions are the page's primary controls, and the actions container (`[data-testid=page-actions]`) is empty when there are none.
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
    <header class="pm-masthead">
      <p class="pm-masthead__kicker"><img class="pm-masthead__logo" data-testid="masthead-logo" …><span class="pm-masthead__kicker-text">{kicker}</span></p>
      <h1 class="pm-page-title" data-testid="page-title">{title}</h1>
      <div class="pm-masthead__lede">{lede}</div>
      <div class="pm-masthead__actions" data-testid="page-actions">{actions}</div>
    </header>
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

### Page layout (reworked 2026-10-24)

An editorial grid with hairline rules instead of boxes. Only the menu floats, and nothing else has a shadow.
- **Masthead:**
  - the kicker starts at the grid's left edge, level with the floating menu button in the top right-hand corner, in capitals (1.25rem; 1rem on phones), led by the logo (the favicon) at the menu button's height and level with it; when the kicker wraps the logo stays beside it
  - the title is flush with the grid's left edge
  - an optional lede follows
  - the actions sit at the right, below the title and just above the rule (level with the lede's last line when there is one)
  - a heavy rule (`--pm-rule-heavy`) closes it
- **Page grid:** `.pm-page` has 12 columns. `.pm-rail` takes columns 1–3 and is sticky; `.pm-pane` takes columns 4–12. The rail holds the page's context: filters, the record's facts (`.pm-facts`, label/value rows on hairlines), the run status, a rule's identity, or notes. The pane holds the content.
- **Sections:** `.pm-section` in the pane starts with `.pm-section__head`, a `.pm-label` heading (small capitals) over a 1px foreground rule, followed by rows on hairlines.
- **Tokens:** `--pm-space-1` to `--pm-space-7` (4, 8, 12, 16, 24, 40 and 64px), `--pm-hairline`, `--pm-rule`, `--pm-rule-heavy`, `--pm-measure` (68ch) and `--pm-actionbar-height`.
- **Below 960px:** one column.
  - `.pm-page--inspector` (a record's facts) and `.pm-page--notes` (a list page's notes) put the rail after the pane.
  - `.pm-collapsible` content (the email filters) is hidden until its `[data-pm-toggle]` button (`.pm-rail__toggle`, shown only here) opens it.
  - The section index is dropped.
- **Below 640px:**
  - The masthead's actions become a bar fixed to the foot of the screen, and toasts rise above it.
  - Ruled rows (flags, events, rules, errors) stack their columns.
  - The prompt editor's text area runs down to the bar.
- **Toggles:** `button[data-pm-toggle="<id>"]` toggles class `pm-open` on that element and keeps `aria-expanded` in step.

### Menu

- **Button.** `button#pm-menu-button.pm-menu-button` (`data-testid="menu-button"`) with `aria-label="Menu"`, `aria-controls="pm-menu"` and `aria-expanded="false|true"`.
  - It's `position: fixed` at the top right (`top` and `right` 0.75rem), with `z-index: 100`, so it floats over the content.
  - The frosted glass is `backdrop-filter: blur(12px) saturate(140%)` (plus `-webkit-backdrop-filter`) over a translucent `--pm-bg-floating`.
- **Panel.** `nav#pm-menu.pm-menu` (`data-testid="menu"`, `aria-label="Main"`) is hidden with the `hidden` attribute when closed. It is also fixed and frosted, below the button and aligned to its right edge.
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
  - Opening moves focus to the first item. Up and Down arrows move between items and **wrap**: Up on the first item goes to the last, and Down on the last goes to the first (WAI-ARIA menu pattern). Home and End jump to the first and last.
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
  - The body may be empty, e.g. `{#modal id="x" title="X"}{/modal}` when HTMX fills it. The tag renders it with `{#insert}`.
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

---

## Phase 3: admin pages

General rules for every page below:
- Routes are under `/mail`. Pages use the layout with the `page` key shown.
- **HTMX actions** respond with fragments. Success adds an `HX-Trigger` `pm-toast` (`success`). Failures add an `error` toast, and validation failures use status **422**.
- **Forms** show a field's error as `<p class="pm-field-error" data-testid="error-<field>">` after the field, and mark the field `aria-invalid="true"`.
- **Not found:** unknown ids give **404**. Deleted rows count as unknown.
- **Refused operations**, such as deleting a system type or prompt, give **409** with an error toast.
- **Icon buttons** on table rows have `aria-label`s, e.g. `Edit Bill` and `Delete Bill`.

### Service layer (`sh.hutch.postmaster.services`)

- **`ValidationException(errors: Map<String, String>)`** is an `IllegalArgumentException`. The keys are form field names (below), and the message is the first error. **`DuplicateNameException(field: String, message: String)`** extends it with `errors = {field: message}`.
- **`OperationNotAllowedException(message)`** is an `IllegalStateException`, mapped to 409. It covers deleting system types and prompts.
- Missing or deleted ids throw `jakarta.ws.rs.NotFoundException`.
- Inputs are models in `sh.hutch.postmaster.models` with raw string fields, so every problem can be reported:
  - `TypeInput(name, description, colour)`
  - `CalendarInput(name, description, colour, enabled: Boolean, notificationPeriods)`
  - `PromptInput(name, contents)`
  - `RuleInput(name, description, priority, conditions: List<ConditionInput>, actions: List<ActionInput>)`
  - `ConditionInput(index: Int, id: Long?, attribute, matchType, test)`
  - `ActionInput(index: Int, id: Long?, actionType, destination, typeId: String?, calendarId: String?, notifications, enabled: Boolean)`

  `index` is the row's form index, used in error keys. `RuleInput` also has `enabled: Boolean?`: null on create, where rules start enabled.

  All the string fields are `String?`.

**`TypeService`**
- Methods: `list()`, `get(id)`, `create(TypeInput)`, `update(id, TypeInput)` and `delete(id)`.
- **name:** required, at most 100 characters after trimming, unique ignoring case among active types (D20).
- **description:** optional (D2). Blank stores NULL.
- **colour:** required, matching `#rrggbb` (case-insensitive), stored lower case.
- **delete:** refused for system types.

**`CalendarService`**
- Methods: `list()`, `get(id)`, `create(CalendarInput)`, `update(id, …)`, `delete(id)`, `setEnabled(id, enabled)` and `khalCalendars(): KhalCalendars`.
- **name:** required and unique ignoring case. It must be one of `khalCalendars().names`, or the calendar's current name when editing (D4).
- **description:** required.
- **colour:** as for types.
- **notificationPeriods:** optional. If given it must pass `IsoDurations.isValidList` (comma plus space); blank stores NULL.

**`KhalConfigService.calendars(): KhalCalendars(names: List<String>, error: String?)`**
- It reads `postmaster.khal-config` and takes the `[[name]]` sections under `[calendars]`, in file order.
- `error` describes why the file couldn't be read, with `names` then empty.

**`PromptService`**
- Methods: `list()`, `get(id)`, `create(PromptInput)`, `update(id, …)`, `delete(id)` and `preview(markdown): String`.
- **name:** required, at most 100 characters.
- **contents:** required.
- **delete:** refused for system prompts.
- **preview:** renders the markdown to HTML with commonmark 0.30.1, escaping raw HTML.
- `sh.hutch.postmaster.models.Placeholders.ALL` is the list of `Placeholder(token, label)`, in this order:

  | Token | Label |
  |---|---|
  | `{{calendars}}` | Enabled calendars and their purposes |
  | `{{events}}` | Events found in the email |
  | `{{subject}}` | Email subject |
  | `{{sender}}` | Email sender |
  | `{{text}}` | Plain text of the email |
  | `{{html}}` | HTML of the email |
  | `{{page}}` | Contents of the page linked from the email |
  | `{{types}}` | Email types and their descriptions |

**`RuleService`**
- Methods: `list(ruleType)` (active, by priority descending then id), `get(ruleType, id)`, `create(ruleType, RuleInput)`, `update(ruleType, id, RuleInput)`, `delete(ruleType, id)` and `setEnabled(ruleType, id, enabled)`.
- **name:** required and at most 100 characters, unique ignoring case within the rule type. It's ignored by `update`, because the name can't be edited.
- **description:** required.
- **priority:** blank means 0. Otherwise it's a whole number of 0 or more.
- **Conditions:** at least one.
  - `attribute` and `matchType` are valid codes, and `matchType.appliesTo(attribute)` holds (Q9).
  - `test` is required (non-blank, at most 1000 characters) when `needsTest`, and stored as NULL otherwise (Q10).
  - A `regex` test must compile.
- **Actions:** at least one.
  - `actionType` must be in `ActionTypes.BUILT_IN`.
  - `move` needs a non-blank `destination`, the folder.
  - `forward_email` needs a non-blank `destination` that looks like an email address.
  - `set_type` needs `typeId`, an active type.
  - `process_bill` needs `calendarId`, an active calendar (Q16).
  - `create_events` takes an optional `calendarId` (when it's missing, the LLM picks a calendar, Q15) and optional `notifications` (an `IsoDurations` list).
  - Fields that don't apply to the action type are stored as NULL.
- **Updates:**
  - Conditions with an `id` are updated, those without are added, and those left out are deleted.
  - Actions with an `id` are updated, including `enabled`; those without are added, and those left out are soft-deleted.
- Error keys for a condition or action row are e.g. `conditions[3].test` and `actions[0].destination`, using the row's form index. Rule-level keys are `name`, `description`, `priority`, `conditions` and `actions`.

### 3.1 Configuration (`page="configuration"`)

| Route | Result |
|---|---|
| `GET /configuration` | the page, with `table#settings-table` (`data-testid="settings-table"`) of every setting in `position` order |
| `GET /configuration/{key}/edit` | the edit fragment |
| `PUT /configuration/{key}` (form field `value`) | **200** with the display fragment and toast `Saved <label>`. No toast when nothing changed, including a blank encrypted value. **422** returns the edit fragment, with the typed value, `aria-invalid` and `[data-testid=setting-error-<key>]`, plus an error toast. |
| `DELETE /configuration/{key}` | clears an **encrypted** setting and returns the display fragment with toast `Cleared <label>`. 409 for other types. |

- **Groups:** settings are grouped by key prefix, one `tbody#settings-<prefix>[data-testid=settings-group-<prefix>]` each, headed by a `tr.pm-group__head` row (e.g. "Mailbox (IMAP)"). The rail holds an index of the groups (`settings-index`), dropped below 960px.
- **Rows** are `tr[data-testid="setting-row-<key>"]`. The label cell has the label, and the description is in `.pm-muted`.
- **Display fragment:** `button.pm-setting-value[data-testid="setting-value-<key>"]`, showing `displayValue` (encrypted ones masked, Q17) or `Not set` (class `pm-muted`). Clicking it swaps in the edit fragment (`hx-get`).
- **Edit fragment:** `span.pm-setting-edit` containing an `input[name=value][data-testid="setting-input-<key>"]` with `autofocus`.
  - The input is `type=password` and empty for encrypted settings, and `type=text` holding the current value otherwise.
  - Losing focus (**blur**) sends the `PUT`. **Enter** blurs, and so saves.
- **Clear button:** encrypted settings that are set also show `button[data-testid="setting-clear-<key>"]`, with a confirm. It sends the `DELETE`.

### 3.2 Types (`page="types"`)

| Route | Result |
|---|---|
| `GET /types` | the page |
| `GET /types/new` | the empty form fragment; `HX-Trigger-After-Swap` opens `type-dialog` |
| `GET /types/{id}/edit` | the filled form fragment; same trigger |
| `POST /types` (create) and `PUT /types/{id}` (update), with form fields `name`, `description`, `colour` | **200** with the table fragment, `HX-Retarget: #types-table-container`, `HX-Reswap: innerHTML`, and `HX-Trigger` with the toast `Created type <name>` or `Saved type <name>` plus `pm-dialog-close` for `type-dialog`. **422** returns the form fragment with errors and an error toast. |
| `DELETE /types/{id}` | the table fragment with toast `Deleted type <name>`; 409 for system types |

- **Page:** `button[data-testid="new-type"]`, in the masthead's actions, opens the dialog. The table sits in `#types-table-container` (the pane), and the rail holds notes. The other list pages (calendars, prompts, rules) follow the same pattern, with their "New …" control in the masthead.
- **Table:** `{#table id="types-table"}` with columns `name` (p1), `description` (p3), `colour` (p2, a swatch `span.pm-swatch` plus hex) and `actions` (p1, not resizable).
  - Rows are `tr[data-testid="type-row-<id>"]`.
  - Each row has `button[data-testid="edit-type-<id>"]` and, except system types, `button[data-testid="delete-type-<id>"]` (`hx-confirm` `Delete type <name>?`, OK label `Delete`).
  - System types show a `span.pm-badge[data-testid="system-badge"]` reading `System`.
- **Dialog:** `{#modal id="type-dialog"}`; its title is `New type` or `Edit type`. The form is `form#type-form[data-testid="type-form"]`, with:
  - inputs `input[name=name]` and `textarea[name=description]`
  - the colour: `input[type=color][data-testid="colour-picker"]` (no `name`) synced with `input[name=colour][data-testid="colour-hex"]`. Changing either updates the other; the hex is what's submitted.
  - the submit button `button[type=submit][data-testid="save-type"]`
- **New type colour:** a new type's colour defaults to `#6272a4`.

### 3.3 Calendars (`page="calendars"`)

- **Routes:** like types, with the paths `/calendars`, `/calendars/new`, `/calendars/{id}/edit`, `POST /calendars`, `PUT /calendars/{id}` and `DELETE /calendars/{id}`, plus `POST /calendars/{id}/enabled` (form field `enabled=true|false`). That returns the table fragment with toast `Enabled calendar <name>` or `Disabled calendar <name>`.
- **Form:** `form#calendar-form` with these fields:
  - `select[name=name]`: the khal calendars, plus the current name when editing
  - `textarea[name=description]`
  - the colour fields as for types
  - `input[name=notificationPeriods]` (placeholder `e.g. 4h, 1d`)
  - `input[type=checkbox][name=enabled][value=true]` (unchecked means false)
  - submit `save-calendar`
- If khal's config can't be read, or lists no calendars, the form shows `[data-testid="khal-error"]` with the reason.
- **Table** (`#calendars-table`, in `#calendars-table-container`):
  - columns `name` (p1), `colour` (p1), `description` (p3), `notifications` (p4), `enabled` (p1) and `actions` (p1)
  - rows `tr[data-testid="calendar-row-<id>"]`
  - the enable switch is `input[type=checkbox][role=switch].pm-switch[data-testid="enabled-calendar-<id>"]`, with `checked` matching the state and `aria-label` `Enabled`. Changing it sends `POST …/enabled`.
  - the row buttons are `edit-calendar-<id>` and `delete-calendar-<id>` (confirm `Delete calendar <name>?`)
  - the dialog is `calendar-dialog`
- **Toasts:** `Created calendar <name>`, `Saved calendar <name>` and `Deleted calendar <name>`.

### 3.4 Prompts (`page="prompts"`)

| Route | Result |
|---|---|
| `GET /prompts` | the list page |
| `GET /prompts/new` | the editor page |
| `POST /prompts` (form fields `name`, `contents`) | **303** to `/mail/prompts` with flash `Created prompt <name>`; **422** re-renders the editor with errors and an error toast |
| `GET /prompts/{id}/edit` | the editor page |
| `POST /prompts/{id}` | **303** to `/mail/prompts` with flash `Saved prompt <name>`; **422** as above |
| `DELETE /prompts/{id}` | the table fragment (`#prompts-table-container`) with toast `Deleted prompt <name>`; 409 for system prompts |
| `POST /prompts/preview` (form field `contents`) | the HTML fragment of the rendered markdown |

- **List page:**
  - `a[data-testid="new-prompt"]` links to the editor.
  - `{#table id="prompts-table"}` has columns `name` (p1), `key` (p3, showing the system badge and key), `updated` (p4, `{#time}`) and `actions` (p1).
  - Rows are `tr[data-testid="prompt-row-<id>"]`, with `a[data-testid="edit-prompt-<id>"]` and, except system prompts, `button[data-testid="delete-prompt-<id>"]` (confirm `Delete prompt <name>?`).
- **Editor page** (`form#prompt-form[data-testid="prompt-form"]`, `method=post`):
  - `input[name=name]`
  - a toolbar with:
    - `select#placeholder-select[data-testid="placeholder-select"]`: one option per placeholder, `value` = the token, text = label + token
    - `button[data-testid="insert-placeholder"]`, which inserts the selected token at the cursor in the text area, replacing any selection, and leaves the cursor after it
    - `button[data-testid="preview-toggle"]` (`aria-pressed`), which switches between the text area and `div#prompt-preview[data-testid="prompt-preview"]`, filled by `POST /prompts/preview`
  - `textarea#prompt-contents[name=contents][data-testid="prompt-contents"]`
  - submit `button[data-testid="save-prompt"]` and a `Cancel` link `cancel-prompt` back to the list, both in the header row beside the name. Toasts at the bottom right would cover them otherwise, and this page doesn't scroll.
- **Editor layout:**
  - The page body gets the class `pm-full-height`. The editor fills the viewport height: the text area grows to fill the remaining space, at least 10rem.
  - The page never scrolls vertically, except when the viewport is too short for that minimum.
- **Read-only key:** system prompts show their key read-only, `[data-testid="prompt-key"]`.

### 3.5 Rule lists (`page="rules-classification"` / `"rules-action"`)

| Route | Result |
|---|---|
| `GET /rules/{type}` (`classification` or `action`; anything else is 404) | the list page |
| `DELETE /rules/{type}/{id}` | the table fragment (`#rules-table-container`) with toast `Deleted rule <name>`. With `?from=edit` (the edit page's delete button) it sends `HX-Redirect: /mail/rules/{type}` and flashes the toast. |
| `POST /rules/{type}/{id}/enabled` (`enabled=true|false`) | the table fragment with toast `Enabled rule <name>` or `Disabled rule <name>` |

- **Page:**
  - The heading is `Classification rules` or `Action rules`.
  - `a[data-testid="new-rule"]` links to `/rules/{type}/new`.
- **Table** (`{#table id="rules-table"}`):
  - Columns are `name` (p1), `description` (p3), `priority` (p2), `conditions` (p4, a count), `actions` (p4, a count), `enabled` (p1) and `controls` (p1).
  - Rows are `tr[data-testid="rule-row-<id>"]`.
  - Each row has the switch `enabled-rule-<id>` (as for calendars), `a[data-testid="edit-rule-<id>"]` and `button[data-testid="delete-rule-<id>"]` (confirm `Delete rule <name>?`).

### 3.6 Create and edit rule pages

| Route | Result |
|---|---|
| `GET /rules/{type}/new` | the create page |
| `POST /rules/{type}` | **303** to `/mail/rules/{type}` with flash `Created rule <name>`; **422** re-renders the page with errors |
| `GET /rules/{type}/{id}/edit` | the edit page |
| `POST /rules/{type}/{id}` | **303** to the list with flash `Saved rule <name>`; 422 as above |

**Form** (`form#rule-form[data-testid="rule-form"]`, `method=post`), with these field names:
- **Rule fields:**
  - `name`: on the edit page a read-only `[data-testid="rule-name"]` text, not an input
  - `description` (textarea)
  - `priority` (`input[type=number][min=0]`, blank means 0)
  - `enabled` (`input[type=checkbox][value=true]`, edit page only)
- **Conditions:** inside `#conditions[data-testid="conditions"]`, each row is `div.pm-rule-row[data-testid="condition-row"]` with:
  - `conditions[i].id` (hidden, for existing rows)
  - `conditions[i].attribute` (select)
  - `conditions[i].matchType` (select)
  - `conditions[i].test` (input)
  - `button[data-testid="remove-condition"]`

  `i` is any distinct integer (not necessarily contiguous); rows keep their order. `button[data-testid="add-condition"]` appends a new row from a `<template>`.
  - The match type options have `data-attributes="<codes they apply to>"`. Options that don't apply to the chosen attribute are disabled and hidden, and an invalid selection moves to the first valid one.
  - The test input is hidden and disabled when the match type takes no test.
- **Actions:** inside `#actions[data-testid="actions"]`, each row is `div.pm-rule-row[data-testid="action-row"]` with:
  - `actions[i].id` (hidden)
  - `actions[i].actionType` (select)
  - `actions[i].destination` (input, labelled `to:`), shown for `move` and `forward_email`
  - `actions[i].typeId` (select of active types), shown for `set_type`
  - `actions[i].calendarId` (select of active calendars, with a `(Choose with the LLM)` empty option for `create_events`), shown for `create_events` and `process_bill`
  - `actions[i].notifications` (input), shown for `create_events`
  - `actions[i].enabled` (`checkbox value=true`; new rows are checked)
  - `button[data-testid="remove-action"]`

  `add-action` appends a row. Hidden fields are disabled, so they aren't submitted.
- **Buttons:**
  - submit `button[data-testid="save-rule"]`
  - `a[data-testid="cancel-rule"]` back to the list
  - on the edit page, `button[data-testid="delete-rule"]`: `hx-delete` with confirm, and on success the response has `HX-Redirect` to the list plus flash `Deleted rule <name>`
- **Remove buttons** are disabled while only one row of their kind is left.
- **Errors** appear as `[data-testid="error-<key>"]`, where the key is e.g. `name`, `conditions`, `conditions[3].test` or `actions[0].calendarId`.
- **The page** gets `page="rules-<type>"`, and its title is `New classification rule`, `Edit action rule` and so on.

---

## Phase 4: integration helpers

These are reusable services (`sh.hutch.postmaster.services`) for the actions and the pipeline. Each reads its settings from `SettingsService` on every call, so a settings change applies at once. Encrypted settings are decrypted there.

Errors from the outside world use one exception per area. Each is a `RuntimeException` whose message is fit to show the user and to store in `Email_errors`:
- `MailboxException`
- `LlmException`
- `FetchException`
- `CalendarException`

The value types are in `sh.hutch.postmaster.models` (`Integrations.kt`).

### 4.1 `MailboxClient` (IMAP and SMTP with Angus Mail 2.0.5)

**Settings:**
- IMAP: `imap.host`, `imap.port`, `imap.security` (`none`, `starttls` or `tls`), `imap.username`, `imap.password`, `imap.inbox_folder` and `imap.trash_folder`
- SMTP: `smtp.host`, `smtp.port`, `smtp.security`, `smtp.username`, `smtp.password` and `smtp.from_address`

**Connections:**
- Every call opens its own connection and closes it.
- Timeouts are 20 s to connect and 60 s to read.
- Messages are read with `mail.imap.peek=true`, so **reading never sets `\Seen`** (D16).

**Methods:**

| Method | Behaviour |
|---|---|
| `listInbox(since: Instant?): List<MailboxMessage>` | INBOX messages received at or after `since` (all of them when null), **newest first** |
| `fetch(folder: String, uid: Long): ParsedMessage` | the message, parsed. Throws `MailboxException` when there's no such UID. |
| `saveAttachment(folder, uid, partPath, target: Path): Long` | writes the part's decoded bytes to `target`, creating parent directories, and returns the size in bytes |
| `move(folder, uid, destination: String)` | uses `MOVE` when the server supports it, otherwise copy, flag `\Deleted` and expunge. The destination folder must exist (`MailboxException` otherwise). |
| `delete(folder, uid)` | moves to `imap.trash_folder` (Q6) |
| `setRead(folder, uid, read: Boolean)` | sets or clears `\Seen` |
| `listFolders(): List<String>` | full names of the folders that hold messages, sorted |
| `forward(folder, uid, to: String)` | sends an inline forward through SMTP (Q7), described below |

**Message types:**
- `MailboxMessage(uid: Long, uidValidity: Long, messageId: String, receivedAt: Instant, subject: String?, sender: String?)`
  - `messageId` is `MessageIds.normalise(header)`. When there's no Message-ID header, it's `MessageIds.generated(receivedAt, sender, subject, uidValidity, uid)`.
  - `receivedAt` is the IMAP INTERNALDATE, or the Date header when that's missing.
- `ParsedMessage(uid, uidValidity, folder, messageId, receivedAt, subject, sender, senderName, recipients: List<String>, text: String?, html: String?, attachments: List<AttachmentPart>, isRead: Boolean)`
  - `sender` is the From address and `senderName` its personal name.
  - `text` is the first `text/plain` part that isn't an attachment. When there's none but there is HTML, it's `HtmlParser.text(html)`.
  - `html` is the first `text/html` part that isn't an attachment.
- `AttachmentPart(filename: String, contentType: String, size: Long, partPath: String)`
  - Attachments are parts with a filename, except inline parts that have a `Content-ID` (embedded images).
  - `partPath` is the 1-based dotted path through the multipart tree, e.g. `2` or `1.2`.
  - `size` is the encoded size the server reports, or -1.

**Inline forward:**
- Subject: `Fwd: <subject>`. From: `smtp.from_address`, or `imap.username` if that's blank.
- The body is `multipart/mixed`, holding:
  - a `multipart/alternative` with a text part and, when the original has HTML, an HTML part. Each starts with the header block below and quotes the original's text or HTML.
  - each original attachment, with its bytes unchanged and always base64-encoded. A text encoding would turn line endings into CRLF.

  The header block:

  ```
  ---------- Forwarded message ---------
  From: <name> <<address>>
  Date: <date header>
  Subject: <subject>
  To: <recipients>
  ```
- `to` must be a valid address (`MailboxException` otherwise).

### 4.2 `LlmClient` and `PromptRenderer`

**`LlmClient`**
- Settings: `llm.url`, `llm.api_key` (when set, it's sent as `Authorization: Bearer …`), `llm.model` and `llm.timeout`.
- `chat(prompt: String, json: Boolean = false): String` sends `POST <llm.url>/chat/completions` (an OpenAI-compatible API) with this body:

  ```json
  {"model": "<llm.model>", "messages": [{"role": "user", "content": "<prompt>"}],
   "temperature": 0, "stream": false,
   "chat_template_kwargs": {"enable_thinking": false},
   "response_format": {"type": "json_object"}}
  ```

  `response_format` is only sent when `json` is true.
  - It returns `choices[0].message.content`, with any `<think>…</think>` block and surrounding whitespace removed.
- `chatJson(prompt: String): JsonNode` calls `chat(prompt, json = true)`. It also strips a surrounding `` ```json `` code fence, and requires the result to be a JSON object.
- Errors raise `LlmException`:
  - `LLM request timed out after <duration>`
  - `LLM returned HTTP <status>: <first 200 characters of the body>`
  - `LLM could not be reached at <url>: …`
  - `LLM response had no content`
  - `LLM response is not valid JSON: …`

**`PromptRenderer.render(promptKey: String, context: PromptContext): String`**
- It loads the active prompt by key and replaces every `{{placeholder}}` in its contents. It throws `IllegalStateException` for an unknown key. Unknown placeholders are left as they are.
- `PromptContext(subject, sender, text, html, page, events: List<PromptEvent>)`, with every field nullable. `PromptEvent(id: Long, title, when: String, location: String?, notes: String?)`.

| Placeholder | Value |
|---|---|
| `{{subject}}`, `{{sender}}` | as given, or empty |
| `{{text}}`, `{{html}}`, `{{page}}` | as given, each **cut to `llm.max_input_chars`** characters |
| `{{calendars}}` | the enabled calendars, one line each: `- <name>: <description>` |
| `{{types}}` | the active types, one line each: `- <name>: <description>` (or just `- <name>`) |
| `{{events}}` | a JSON array of `{"id", "title", "when", "location", "notes"}` |

### 4.3 `HttpFetcher` and `HtmlParser`

**`HttpFetcher`**
- Settings: `fetch.user_agent` and `fetch.timeout`. Commands: `postmaster.chromium-command` and `postmaster.curl-command`.
- Only `http` and `https` URLs are accepted (`FetchException` otherwise).
- **`fetchPage(url): String`** returns the rendered DOM. It runs `<chromium> --headless=new --disable-gpu --no-sandbox --disable-dev-shm-usage --no-first-run --user-agent=<ua> --dump-dom <url>`. Failures and timeouts raise `FetchException`.
- **`download(url, target: Path): DownloadedFile(path, contentType: String?, size: Long)`** runs `<curl> --silent --show-error --location --fail --max-time <seconds> --user-agent <ua> --output <target> --write-out %{content_type} <url>`.
  - It creates parent directories.
  - A non-zero exit raises `FetchException`, with curl's stderr.
- Commands run with arguments in an array (no shell), and the timeout kills the process.

**`HtmlParser`** (object)
- `text(html): String`: readable text, with blocks and `<br>` on separate lines and runs of blank lines collapsed.
- `links(html, baseUrl: String? = null): List<Link(text, href)>`: every `a[href]`, resolved against `baseUrl`.
- `findLinks(html, anchorTexts: List<String>, baseUrl: String? = null): List<Link>`: links whose trimmed text (or `aria-label`, or `title`) **contains** any of `anchorTexts`, ignoring case. They're returned in the order of `anchorTexts`, then document order.

### 4.4 `CalendarClient` (khal)

- Settings: `processing.timezone`. Commands: `postmaster.khal-command` and `postmaster.khal-config`.
- **`calendars(): List<KhalCalendar(name, path: Path)>`** comes from the khal config's `[[name]]` sections and their `path`. A leading `~` is expanded to the user's home directory, and quotes are removed.
- **`addEvent(calendar: String, event: CalendarEvent): String`**
  - It writes a temporary `.ics` and runs `<khal> -c <khal-config> import --batch -a <calendar> <file>`. A non-zero exit raises `CalendarException` with khal's output. It returns the UID.
  - `CalendarEvent(uid: String?, title, startsAt: LocalDateTime, endsAt: LocalDateTime?, allDay: Boolean, location: String?, notes: String?, alarms: List<Duration>)`. A missing `uid` is generated as `pm-<uuid>`.
- **`moveEvent(uid, from: String, to: String)`** moves the vdir file holding `UID:<uid>` from one calendar's directory to the other's.
- **`deleteEvent(uid, calendar)`** deletes that file.
- A missing event or an unknown calendar raises `CalendarException`.

**The `.ics` written** (`IcsWriter.event(event, zone): String`, an object):
- `PRODID:-//hutch.sh//Postmaster//EN`, `VERSION:2.0`, CRLF line endings, lines folded at 75 octets, and text escaped (`\`, `;`, `,` and newlines).
- **Timed events:** `DTSTART`/`DTEND` in UTC (`…Z`), converted from the local wall-clock time in `processing.timezone`. `DTEND` is `endsAt`, or `startsAt` plus 1h.
- **All-day events:** `DTSTART;VALUE=DATE:yyyyMMdd` and `DTEND;VALUE=DATE` the next day.
- One `VALARM` (`ACTION:DISPLAY`, `TRIGGER:-<ISO duration>`) per alarm.

---

## Phase 5: processing pipeline

### 5.1 Rule engine (`services.RuleEngine`)

**What a rule tests:** `EmailFacts(subject, sender, senderName, message, events: List<String>, attachments: List<String>, billUrl, type, link)`, in `models`. All the scalar fields are `String?`. The facts are built from the email record:
- `message` is the plain text.
- `events` are the event titles, and `attachments` the stored attachment filenames.
- `billUrl` is `Emails.bill_download_url`.
- `type` is the type's name.

**Methods:**
- `matches(rule: RuleEntity, facts): Boolean` is true when **every** condition matches (Q21), or **any** of them for a rule with `conditionJoin = ANY` (R8). A rule with no conditions never matches.
- `conditionMatches(condition, facts): Boolean`

**Matching by match type:**

| Match type | Scalar attributes | List attributes (events, attachments) |
|---|---|---|
| `is` | the trimmed value equals the trimmed test, ignoring case | |
| `contains` | the value contains the test, ignoring case | |
| `starts_with` | the trimmed value starts with the trimmed test, ignoring case | |
| `regex` | `Regex(test).containsMatchIn(value)`, case-sensitive unless the pattern uses `(?i)` | any item matches |
| `has` | not used (Q9) | the list is not empty (Q10) |
| `is_present` | the value is not blank | the list is not empty |

- A null scalar value matches nothing except `is_present` (which is false).
- An invalid regex matches nothing.
- **`sender`:** `is` and `starts_with` compare the address. `contains` and `regex` match either the address or the display name.

**Rule order** is `RuleRepository.listEnabled(type)`: priority descending, then oldest first. Only enabled, active rules count.

### 5.2 Action framework (package `sh.hutch.postmaster.actions`)

**`interface ActionHandler`**
- `val actionType: String`
- `val stage: ActionStage`
- `val order: Int`, which orders handlers within a stage
- `val repeatable: Boolean` (default `true`): whether running the action again after an interruption is safe (R6). `forward_email` isn't.
- `fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome`

A handler is an `@ApplicationScoped` bean. `ActionRegistry` (`handler(type)` and `types()`) collects every `ActionHandler` bean, so **a new action type is just a new bean**. `RuleService` validates action types against `ActionRegistry.types()`.

**`enum ActionStage`**, in execution order:

| Stage | Built-in actions |
|---|---|
| `RECORD` | `set_type`, `set_reply_needed`, `skip_processing` |
| `GATHER` | `fetch_link_contents` (10), `check_for_events` (20), `download_attachments` (30), `create_events` (40), `process_bill` (50), `update_todo_list` (60) |
| `FORWARD` | `forward_email` |
| `READ_STATE` | `mark_read`, `mark_unread` |
| `MOVE` | `move`, `delete` (last) |

**`ActionOutcome(status: ActionResultStatus, detail: String?)`**
- Factories: `done(detail)`, `skipped(detail)` and `failed(detail)`.
- A handler that throws is recorded as `failed` with the exception's message.

**`ProcessingContext`** holds:
- the `EmailEntity`
- the `ParsedMessage`, when there is one
- `facts()` (current, rebuilt on each call)
- `stopped`

It records results through the orchestrator.

**When actions run (Q12):**
- **Classification rule:** its `RECORD` actions run at once. `fetch_link_contents` and `check_for_events` run in the LLM phase, after Summarise. Every other action is queued for the action phase.
- **Action rules:** all their actions are queued.
- **The queue** runs in stage order, then handler order, then rule order (priority). In the `MOVE` and `READ_STATE` stages, rule order comes **before** handler order, so the highest-priority rule's action is the one that runs.
- **Conflicts:**
  - Only the first `MOVE`-stage action runs (the highest-priority rule, then first in the rule). Every other is recorded as `skipped` with detail `Conflicts with <type> from rule <name>`, plus an `action` error.
  - `READ_STATE` actions conflict the same way.
- **`skip_processing`** stops processing at once (`stopped`). Remaining queued actions are recorded as `skipped`, with detail `Processing was stopped by skip_processing`.

**Resuming (R6):** `ActionExecutor.next(context, ordered, startedNow)` works out the next action to run from the results already recorded, so the queue can carry on after an interruption. An action with a result doesn't run again. An action that isn't `repeatable` is recorded as `started` (and that saved) before it runs; one found `started` by a later pass is recorded as `failed` ("Processing was interrupted while this ran…") instead of running again. Repeatable actions notice what an interrupted run did:
- `move` and `delete` record the move when the message is already in the destination;
- `create_events` and the bill reminder use a UID made from the email and event (`CalendarClient.eventUid`), and `addEvent` doesn't add a UID that's already in the calendar;
- `update_todo_list` saves an item's name before writing its files, and writing again finishes a write cut short.

**Recording:**
- Every executed or skipped action becomes an `Email_action_results` row: `source=rule`, `rule_id`, `rule_action_id`, `action_type`, `status` (`done`, `skipped`, `failed`, or `started` while a non-repeatable action runs) and `detail`.
- Failures also add an `Email_errors` row: phase `classification` for an action run during classification, otherwise `action`. The message is `<action_type>: <detail>`.
- Every matching rule becomes an `Email_rule_matches` row, with `position` in the order they matched.

### 5.3 Handlers

| Action | What it does |
|---|---|
| `set_type` | `email.assignType(action.type)` |
| `set_reply_needed` | `responseRequired = true` |
| `skip_processing` | stops processing |
| `mark_read` / `mark_unread` | `MailboxClient.setRead` on the email's folder and UID, then `isRead` |
| `move` | `MailboxClient.move` to `destination`, then `folder` and `movedTo` = destination. `imapUid` is re-found with `MailboxClient.findUid(folder, messageId)`, which can be null. |
| `delete` | `MailboxClient.delete` (to `imap.trash_folder`), then `folder` and `movedTo` = the trash folder. The record is kept. |
| `forward_email` | `MailboxClient.forward(folder, uid, destination)` |
| `download_attachments` | Saves each attachment not yet downloaded to `<attachments.path>/<yyyy>/<MM>/<email id>/<sanitised filename>`. Sets `stored_path` (relative) and `downloaded_at`. |
| `fetch_link_contents` | Needs `email.link` (`skipped` with `No link to fetch` otherwise). `HttpFetcher.fetchPage`, then `link_contents` = `HtmlParser.text(page)` and `link_summary` = the LLM's answer to `summarise_page`, with `{{page}}` = the page HTML. |
| `check_for_events` (Q11) | When the email has no events yet, the LLM's `identify_events` JSON is stored as `Email_events`. Each gets a suggested calendar from the LLM's `select_calendar` map (event id to calendar name). Nothing is added to a calendar. |
| `create_events` | Identifies events first if there are none. Then each event not yet added goes to a calendar, chosen in this order: the action's calendar, then the event's suggested calendar, then `select_calendar`. Events with no calendar are left out, and the detail says so. Alarms are the action's `notifications`, else the calendar's `notification_periods`. `CalendarClient.addEvent`, then `is_added`, `khal_uid` and `calendar_id`. |

**Event times from the LLM (`when`):**
- `yyyy-MM-dd` gives an all-day event.
- An ISO local date-time gives a timed event, `ends_at` = start + `events.default_duration`.
- Anything else is an `llm` error, and the event is left out.

**`MailboxClient.findUid(folder: String, messageId: String): Long?`** is new. It searches by Message-ID header and returns null when the message isn't found or the id is a generated one.

### 5.4 `process_bill` (`actions.ProcessBillHandler`)

- **Not a bill:** `skipped` unless the email's type is the Bill system type.
- **Amount:** matches of `\$\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)` in the email text. The first match within 80 characters after one of `amount due`, `total due`, `balance due`, `amount payable`, `total` or `due` (ignoring case) wins, otherwise the first match.
- **Due date:** `(due|pay) by` or `due date`, then up to 60 characters, then a date:
  - `d MMM yyyy` or `d MMMM yyyy`, with any capitalisation, an optional leading zero and optional `st`/`nd`/`rd`/`th`
  - or `d/M/yyyy`
- **Links:**
  - The pay link is the first `HtmlParser.findLinks(html, bills.pay_link_text)` match.
  - The bill link, used only when there's no PDF attachment, is the first `bills.bill_link_text` match.
- **Biller:** the sender's display name, else the sender's domain.
- **LLM fallback:** when the amount, due date or pay link is missing, the `bill_details` JSON (`organisation`, `due_date`, `amount_due`, `payment_link`, `bill_url`) fills only the missing fields.
- **Stored:** `bill_sender`, `bill_amount`, `bill_due_date`, `bill_pay_link` and `bill_download_url`.
- **Reminder:** an all-day event on the due date minus one day, in the action's calendar.
  - Title: `Pay <biller> $<amount>`, or `Pay <biller>` with no amount.
  - Notes: `Biller: …`, `Amount: $…`, `Due: yyyy-MM-dd`, `Pay: <link>`.
  - It's stored as an `Email_events` row with `is_added = true`.
  - If the email already has that reminder, e.g. when it's reprocessed, no new one is added.
  - With no due date, there's no reminder and an `action` error is added.
- **Bill download:** a PDF attachment is saved as for `download_attachments`. Otherwise the bill link is downloaded with curl to the same directory, and the file is stored as an `Email_attachments` row (filename from the URL, or `bill.pdf`).
- **Outcome:** `done` with a summary of what was found. `failed` only if nothing at all was found.

### 5.5 Orchestrator (`services.ProcessingService`)

**`runNow(trigger: RunTrigger): ProcessingRunEntity?`** runs synchronously. It returns null if a run is already in progress (one at a time, process-wide) or the application is stopping.
- It records a `Processing_runs` row: `running`, then `succeeded` or `failed` with `error`.
- Counts: `emails_found` (new messages listed), `emails_processed` (including emails carried on with) and `emails_failed` (messages that couldn't be fetched or saved).

**Processing in saved steps (R6).** An email is saved before anything is done with it, then again after each step, each step in **its own transaction**. `Emails.progress` is the last step saved (`recorded`, `classified`, `summarised`, `matched`; null once finished) and `Emails.attempts` counts the passes started. A pass that stops part-way (a crash, a restart, a failure saving) loses at most the step it was in, and the email stays `pending`.

**Each run:**
1. `TodoListService.syncCompleted()`.
2. Every `pending` email (oldest first) is carried on with from the step after its `progress`.
3. `MailboxClient.listInbox(since)`, newest first, where `since` is `processing.lookback` before the latest **successful** run started (or before now if there's none), so email that arrived while Postmaster was down is still found. Messages whose Message-ID `existsByMessageId` are skipped (Q14).
4. Each new message is fetched and saved (`status=pending`, `progress=recorded`), then processed. If fetching or saving fails, nothing has been done with it and the next run tries again (counted as failed).

**The steps:**
1. **Record:** the parsed fields and the attachments whose extension is in `attachments.extensions` (embedded images are already left out).
2. **Classification:** the first matching classification rule only. The match is recorded and its `RECORD` actions run. `progress=classified`.
3. If processing was stopped (a `skip_processing` result), the remaining actions are recorded as skipped and `status=skipped`.
4. **LLM** (Q15), with no transaction open while the LLM answers:
   - With no type yet: the `classify` prompt (plain chat). The answer is trimmed and compared with type names, ignoring case. `NO CLASSIFICATION` or an unknown name leaves the email unclassified; an unknown name is also recorded as an `llm` error.
   - Then always `summarise` (`chatJson`), which stores:
     - `summary`
     - `link`, unless one is already set
     - `response_required` (OR-ed)
     - the `action_items` (or, from an older prompt, `action_required` and `action` as one item), through `addActionItem`, so never for Bill (Q13); a `due` that isn't a date is an `llm` error
     - the `events`, except those the email already has (same title and start)
     - each `errors` entry, as an `llm` error
   - **If the LLM can't be reached or is busy** (`LlmException.transient`, after `LlmClient`'s retries) and the email has attempts left, nothing is saved and the email waits for the next run, before any action has run. On the last attempt the failure becomes an `llm` error and processing carries on, as does any other LLM failure (e.g. an answer that isn't JSON). `progress=summarised`.
   - Then any `fetch_link_contents` and `check_for_events` from the classification rule.
5. **Action rules:** every matching action rule, in order, with facts recomputed after the LLM phase. Matches are recorded. `progress=matched`.
6. **Actions**, one per transaction, as in 5.2.
7. **Finish:** `status=processed` (`failed` if an unexpected exception escaped; errors alone don't fail it), `progress=null` and `processed_at` = now. Bill emails have their action items dropped.

An email is given up on when a pass would be its sixth (`MAX_ATTEMPTS = 5`): an `action` error says so and `status=failed`. One pass works on an email at a time.

**`reprocess(emailId)`:**
- It refuses (`IllegalStateException`) while the email is being processed.
- It finds the message again in its folder (`MailboxClient.locate`, checking UIDVALIDITY) and fetches it.
- It clears the email's rule matches, action results, errors, events not yet added, type, summary, link, link contents and summary, bill fields, flags and action items not in the to-do list, and resets `progress=recorded` and `attempts=0`.
- It reruns the steps from 2. If the LLM can't be reached, the email is left `pending` for the next run. A message that can't be found raises `MailboxException`.

**Startup:** runs left `running`, e.g. after a crash, are marked `failed` (`error = Interrupted by a restart`). Emails left `pending` are carried on with by the next run.

**Shutdown:** on `ShutdownEvent` no further run or step starts, and the current step gets up to 20 seconds to finish. Whatever is left is carried on with after the restart.

**Threads:** `runNow` works from any thread, including ones Quarkus doesn't manage (e.g. the common fork-join pool). A run sets the thread's context class loader to the application's for its duration, and `MailboxClient` does the same around Jakarta Mail, which finds its providers through it.

### 5.6 Scheduler (`services.ProcessingScheduler`)

- `@Scheduled(every = "60s", concurrentExecution = SKIP)` checks whether a run is due: no run yet, or the latest run started at least `processing.frequency` ago. If one is due, it calls `runNow(SCHEDULE)`. A frequency change therefore applies within a minute, with no restart.
- **Off switch:** the new startup key `postmaster.scheduler-enabled` (default `true`) turns the scheduler off. Tests should set it to `false`.
- **Run now:** `ProcessingService.runNowAsync(): Boolean` starts a manual run on a worker thread and returns false if a run is already going. The UI uses it in 6.3.

---

## Phase 6: email UI and dashboard

### Service layer

**`services.EmailService`** is shared by the UI and the MCP server (Phase 7). It returns view models from `models` (`EmailViews.kt`), built inside its transaction, so templates and JSON never load anything lazily.
- `search(EmailFilter): EmailPage`
  - Unfiltered: the 30 (`RECENT`) most recently received active emails, not paginated.
  - Filtered: pages of 50 (`PAGE_SIZE`), newest first. A page number past the end shows the last page.
- `EmailFilter(from, to, status, typeId, sender, responseRequired, actionRequired, withErrors, page)`, all optional and combined with AND:
  - `from` and `to` are inclusive `LocalDate`s in `processing.timezone`.
  - `typeId` is `EmailFilter.UNCLASSIFIED` (0) for emails with no type.
  - `sender` matches the address or display name, ignoring case.
- `detail(id): EmailDetail`: everything on the detail page, including events (`EventView`), attachments (`AttachmentView`), rule matches with their action results (`RuleResults`), manual actions, errors and the enabled calendars.
- **Manual actions** return `ManualResult(ok, message)`. Each is recorded as an `Email_action_results` row with `source` `ui` or `mcp` and no rule. A failure is recorded as `failed`, plus a `manual` error, and is **not** thrown. Bad input throws `ValidationException`; a missing email or event throws `NotFoundException`.

  | Method | `action_type` recorded |
  |---|---|
  | `setType(id, typeId?, source)` (null clears the type; Bill clears action required) | `set_type` |
  | `move(id, folder, source)` | `move` |
  | `setRead(id, read, source)` | `mark_read` / `mark_unread` |
  | `delete(id, source)`: to `imap.trash_folder`, then soft-deletes the email (D11) | `delete` |
  | `forward(id, to, source)` | `forward_email` |
  | `setResponseRequired(id, required, source)` | `set_response_required` |
  | `setActionRequired(id, required, description?, source)`: setting it adds an item (unless one is open and there's no description), and fails on a Bill (Q13); clearing it completes every open item, in the to-do list too | `set_action_required` |
  | `completeActionItem(emailId, itemId, source)`: ticks the item off in the to-do list (`TodoListService.complete`), then marks it done; if the files can't be written nothing changes | `complete_action_item` |
  | `changeEventCalendar(emailId, eventId, calendarId, source)`: an added event is moved in khal (`CalendarClient.moveEvent`); one not yet added is added to the chosen calendar, with the calendar's notification periods | `change_event_calendar` |
- `reprocess(id): ManualResult` calls `ProcessingService.reprocess`.
- `folders()` lists the IMAP folders.
- `attachmentFile(emailId, attachmentId): StoredFile(attachment, mediaType, path)`. Throws 404 unless the attachment was downloaded and its file is inside `attachments.path`.
- **Previews:** `EmailService.previewKind(mediaType)` is `pdf` (`application/pdf`), `image` (PNG, JPEG, GIF, WebP) or `text` (`text/plain`), and null otherwise. HTML and SVG are never shown inline.

**`services.DashboardService.dashboard(): Dashboard`** returns:
- `running`
- `latest` (`RunView`)
- `nextRunAt`: the latest start plus `processing.frequency`, or now if that has passed; null when the scheduler is off
- `frequency`
- `schedulerEnabled`
- `counts` (`EmailCounts`: total, last day, with errors, response required, action required, unclassified)
- `recentRuns`: the last 10

### 6.1 Emails list (`page="emails"`)

`GET /emails` takes query parameters `from` and `to` (`yyyy-MM-dd`), `status` (code), `type` (id, or `none`), `sender`, `response=true`, `action=true`, `errors=true` and `page`. Values that don't parse are ignored.
- **Filter form:** `form#email-filters[data-testid=email-filters]` (`method=get`) is in the rail and always shown on wide screens. Below 960px it folds behind the button `toggle-filters`, which reads "Filters" plus, when filters are set, `[data-testid=active-filters]` with how many (the date range counts once). The form has:
  - the date range `filter-from` and `filter-to` (one fieldset)
  - `filter-status`, `filter-type`, `filter-sender`
  - checkboxes `filter-response`, `filter-action` and `filter-errors`
  - `apply-filters`

  The link `clear-filters` ("Clear filters") is above the form, and only shown when filters are set.
- **Count line:** `[data-testid=email-count]`, the masthead's lede, reads `The N most recently received emails` (unfiltered) or `N matching emails`.
- **Table** `{#table id="emails-table"}`, with columns (default widths in CSS; the subject takes the rest):
  - `received` (p1, `{#time}`)
  - `subject` (p1, a link `email-link-<id>`)
  - `sender` (p2, the name, with the address as a tooltip)
  - `type` (p3, the type as a badge in its colour, `type-badge`)
  - `needs` (p2, badges `needs-response` "Reply" and `needs-action` "Action")
  - `status` (p4, a badge `status-badge` only for statuses other than `processed`)
  - `counts` "Items" (p3): `count-events` and `count-attachments` (icon and number, faded when 0), and `error-count` only when there are errors
- **Rows** are `tr[data-testid=email-row-<id>][data-pm-href=/mail/emails/<id>]`. A click anywhere on a row, other than on a control, opens the email. Ctrl/Cmd-click opens it in a new tab.
- **Rows with errors** have class `pm-row--error` and `data-error="true"`, with a red tint.
- **Unread emails** have class `pm-row--unread` and `data-unread="true"`, and are bold.
- **Empty list:** `[data-testid=no-emails]`.
- **Pagination**, filtered with more than one page: `nav[data-testid=pagination]` with `previous-page`, `page-number` (`Page 2 of 3`) and `next-page`. The links keep the filters.

### 6.2 Email detail (`page="emails"`)

| Route | Result |
|---|---|
| `GET /emails/{id}` | the page; 404 for unknown or deleted emails |
| `DELETE /emails/{id}` | `HX-Redirect: /mail/emails`, flash `Deleted the email: Moved to Trash` |
| `POST /emails/{id}/read` (`read=true\|false`) | |
| `GET /emails/{id}/move` | form fragment for `move-dialog` (`select[name=folder]`, `data-testid=move-folder`; the current folder isn't offered). A mailbox failure is 502 with an error toast. |
| `POST /emails/{id}/move` (`folder`) | 422 if blank |
| `POST /emails/{id}/type` (`typeId`; blank clears) | 422 for an unknown type |
| `POST /emails/{id}/response-required` and `/action-required` (`required=true\|false`) | |
| `POST /emails/{id}/action-items/{itemId}/done` | 404 for an unknown item; 502 if the to-do list can't be written |
| `POST /emails/{id}/reprocess` | |
| `GET /emails/{id}/events/{eventId}/calendar` | form fragment for `event-calendar-dialog`: radio buttons `calendarId` (`calendar-choice-<id>`), `cancel-calendar` and `save-calendar` |
| `POST /emails/{id}/events/{eventId}/calendar` (`calendarId`) | 422 unless it's an enabled calendar |
| `GET /emails/{id}/attachments/{aid}` | the file, `Content-Disposition: attachment`. With `?inline=true`, previewable types are sent `inline`. Always `X-Content-Type-Options: nosniff`. |
| `GET /emails/{id}/attachments/{aid}/preview` | fragment for `attachment-dialog`: an `iframe` (PDF, text) or `img` (`preview-image`) of `…?inline=true`. 404 if it can't be previewed. |

- **Manual actions** that succeed respond `HX-Redirect` to `/mail/emails/{id}`, flashing a success toast with the result's message. Failures are **502** with an error toast; nothing is swapped.
- **Page:**
  - **Masthead:**
    - the kicker links back (`back-to-emails`) and shows `email-from` and the received time
    - the lede `email-byline` shows the type badge, status and unread
    - the actions are `mark-read`/`mark-unread`, `move-email`, `reprocess-email` (confirm) and `delete-email` (confirm, set apart)
  - **Pane, in order:**
    - the flags `email-flags`, shown only when set, as ruled lines: each action item, `action-item-<id>` ("Action needed", or "Done" with `data-completed="true"`; `action-item-description`, optional `action-item-due`, `action-item-todo` and `action-item-notes`; `complete-action-item-<id>` "Done" while it's open) and `email-response-required` ("Reply needed" and `clear-response-required` "Replied")
    - the summary `email-summary`, as a lede
    - the sections
  - **Layout:** the details are the rail (an inspector, `.pm-page--inspector`), after the pane below 960px.
  - **Details** `dl[data-testid=email-details]`:
    - the type form `type-form` (`select[name=typeId]` `email-type`), which saves when the selection changes (`hx-trigger="change"`)
    - `email-received`, `email-processed`, `email-status`, `email-folder`, `email-read`, `email-link`
  - **Bill:** `bill-details`, holding `bill-biller`, `bill-amount`, `bill-due`, `bill-pay-link` and `bill-download-url`.
  - **Collapsed contents:** `details[data-testid=full-message]` (closed at first) and `details[data-testid=linked-page]` (with `link-summary` and `link-contents`), the latter only when a page was fetched.
  - **Events:** `ul[data-testid=events]`. Each is `button[data-testid=event-<id>]`, with the CSS variable `--pm-event-colour` set to its calendar's colour and `[data-testid=event-calendar]` showing the calendar, or `Not added (suggested: …)`. Events not added have class `pm-event--pending`. Clicking one opens the calendar dialog.
  - **Attachments:** `ul[data-testid=attachments]` with `attachment-<id>`. Downloaded ones have `download-attachment-<id>`, and previewable ones also `preview-attachment-<id>`.
  - **Rules:** `matched-rules`, holding:
    - `classification-rule` / `action-rule` blocks (`data-rule-id`) linking to the rule, or `no-classification-rule` / `no-action-rules`
    - `manual-actions` for actions done by hand

    Each action result is `li[data-testid=action-result][data-action=<type>][data-status=<status>]`. It shows the action's label (`ActionRegistry.label`, e.g. "Set reply needed"), with the type as a tooltip.
  - **Errors:** `ul[data-testid=email-errors]`.

### 6.3 Dashboard (`page="dashboard"`)

| Route | Result |
|---|---|
| `GET /` | the page |
| `GET /dashboard` | the dashboard fragment |
| `POST /runs` | starts a run in the background (`runNowAsync`): the fragment with toast `Processing started`, or **409** with the error toast `Processing is already running` |

- **Fragment** `section#dashboard[data-testid=home-page]`. While a run is in progress it has `data-running="true"` and polls itself (`hx-get=/mail/dashboard`, `hx-trigger="every 2s"`, `hx-swap=outerHTML`). Polling stops when the run ends.
- **Run status** `processing-status` is the rail: the state over a coloured edge (green, red when the last run failed, the accent while running), its facts, the next run and the button. It holds `run-state` (`Running now…`, `Last run <status>` or `No runs yet`), `run-error`, `next-run` (`Every 1h; next …` or `Scheduled processing is off`) and the button `run-now` (disabled while running).
- **Ledger of counts** (`.pm-ledger`) at the top of the pane: figures over labels, divided by hairlines; 3 a row below 960px and 2 below 640px. Each is a link to the filtered list: `count-total` (with the last 24 hours), `count-response`, `count-action`, `count-unclassified` and `count-errors`. A count that needs attention (reply, action or errors above 0) has a coloured marker on the rule above it.
- **Recent runs:** `{#table id="runs-table"}`, rows `run-row-<id>`. Failed runs have `pm-row--error`.

---

## Phase 7: MCP server

- **Transport:** Streamable HTTP at **`/mail/mcp`**, the extension's default `/mcp` under the HTTP root path. A client sends `initialize`, keeps the `Mcp-Session-Id` response header, and sends it with `notifications/initialized` and every later request.
- **Tools:** `controllers.McpTools`. Each tool answers with one text content item holding JSON, or a plain message for actions. Times are ISO 8601 (`2026-10-21T00:00:00Z`).
- **Errors:** bad arguments, unknown ids and failed actions are tool errors (`isError: true`) with the reason as text. Failed actions are also recorded against the email, as for the UI.
- **Recording:** actions use `EmailService` with `source = mcp`.

| Tool | Arguments | Result |
|---|---|---|
| `search_emails` | optional `from`, `to` (`yyyy-MM-dd`), `status`, `type` (name or `none`), `sender`, `response_required`, `action_required`, `with_errors`, `page` | `{total, page, pageCount, emails: [EmailRow]}`. No filters means the 30 most recent; otherwise pages of 50. |
| `get_email` | `id` | `EmailDetail` |
| `set_email_type` | `id`, `type` (name; empty clears it) | message |
| `move_email` | `id`, `folder` | message |
| `mark_email_read` | `id`, `read` (default true) | message |
| `delete_email` | `id` | message |
| `forward_email` | `id`, `to` | message |
| `set_response_required` | `id`, `required` | message |
| `set_action_required` | `id`, `required`, optional `description` (refused for bills) | message |
| `complete_action_item` | `email_id`, `item_id` | message |
| `set_event_calendar` | `email_id`, `event_id`, `calendar` (enabled calendar name) | message |
| `reprocess_email` | `id` | message |
| `list_types` | | `{types: [{id, name, description}]}` |
| `list_folders` | | `{folders: [name]}` |
| `list_calendars` | | `{calendars: [{id, name, colour}]}` |

Type and calendar names are matched ignoring case.

## Status endpoint (R7)

- **Routes:** `GET /mail/health` (`HealthController`) and `GET /mail/status` (`StatusController`), both `application/json` with `Cache-Control: no-store`. HTTP 503 when `status` is `DOWN`, otherwise 200. When the most preferred acceptable type is `application/health+json` (not a wildcard), the body is `HealthService.draft(report)` (`DraftHealthReport`) as `application/health+json`, with the same HTTP code.
- **Body:** `HealthReport` (`models/Health.kt`): `status` (`OK`, `DEGRADED`, `DOWN`), `time`, `application` (`ApplicationInfo`), `processing` (`ProcessingInfo`, or null if the database can't be read) and `checks` (`database`, `dovecot`, `llm`, `smtp`, `settings`, `processing`, `khal`, `chromium`, `curl`, `attachments`, `todo`, `disk` → `DependencyCheck`, `status` `UP`/`WARN`/`DOWN`). Every field is always present, null when there's no value. `DraftHealthReport` follows the IETF draft: `pass`/`warn`/`fail`, `checks` keyed `uptime` and `<check>:responseTime`, null fields left out.
- **Checks** run in parallel on the managed executor, each with `HealthService.CHECK_TIMEOUT` (5 s) and no retries: `AgroalDataSource` connection `isValid`, `MailboxClient.checkImap` (login), `MailboxClient.checkSmtp` (connect, with auth if `smtp.username` is set), and `LlmClient.checkModels` (`GET <llm.url>/models`). Also: `SettingsService.undecryptable()`; `khal -c <config> printcalendars` plus `Files.isWritable` on each calendar path; `<chromium|curl> --version`; `attachments.path` and `todo.path` writable or creatable, and `index.md` (if present) containing `TodoList.END_MARKER`; usable space on the distinct `FileStore`s of those folders and the calendars against `health.min_free_mb` (`WARN`). `processing` is worked out from `ProcessingInfo` by `HealthService.processingOutcome`: `WARN` if scheduled and no success within 2 × `processing.frequency` of max(last success, startup), or if `pendingEmails` > `health.pending_limit`. `database` and `dovecot` are critical (`DOWN` overall); any other check not `UP` gives `DEGRADED`.
- **Version:** `BuildInfo` reads `build-info.properties` (written by the Gradle `buildInfo` task from `version.properties`): `version` = `v<build number>`, `commit` = `-PgitCommit` or `git rev-parse --short=12 HEAD` (`-dirty` with uncommitted tracked changes), `builtAt` only for `quarkusBuild`. A successful `quarkusBuild` raises `version.properties` unless `-PkeepVersion` is given.
