# Configuration

Postmaster has two kinds of configuration:
- **The startup file:** the YAML file passed as the application's only argument. It holds what's needed to start.
- **Settings:** stored in the database and edited on the Configuration page. They hold everything else, and changes apply at once without a restart.

## The startup file

See `config/postmaster.example.yml`.

| Key | Required | Default | Meaning |
|---|---|---|---|
| `quarkus.datasource.jdbc.url` | yes | | MariaDB connection, e.g. `jdbc:mariadb://mariadb:3306/postmaster` |
| `quarkus.datasource.username` | yes | | Database user |
| `quarkus.datasource.password` | yes | | Database password |
| `postmaster.encryption-key` | yes | | Base64 of 32 random bytes (`openssl rand -base64 32`): the AES-256 key for encrypted settings. If it changes, encrypted settings must be entered again. |
| `postmaster.khal-config` | yes | | Path of the khal configuration file. Calendars are chosen from its `[[name]]` sections. |
| `postmaster.khal-command` | no | `khal` | Command that runs khal |
| `postmaster.chromium-command` | no | `chromium` | Command that runs headless Chromium |
| `postmaster.curl-command` | no | `curl` | Command that runs curl |
| `postmaster.scheduler-enabled` | no | `true` | Whether email is processed on a schedule. "Run now" works either way. |

Any other Quarkus setting can be added too, e.g. `quarkus.http.port` (default 8080) or `quarkus.log.level`. The application is served under `/mail`.

## Settings

Encrypted settings are stored AES-256-GCM encrypted, as `enc:` followed by base64. They're shown masked; entering a new value replaces them, and leaving the field empty keeps the old one. Durations take full or simplified ISO 8601 durations: `PT1H`, `1H`, `1h`, `1d`, `30m` or `1h30m`. Lists are comma separated.

| Key | Default | Meaning |
|---|---|---|
| `processing.frequency` | `1h` | How often the inbox is checked. At least 1 minute. |
| `processing.lookback` | `7d` | Only INBOX email received within this period is processed. |
| `processing.timezone` | `Australia/Melbourne` | Timezone for event times and for showing times. |
| `imap.host` | `mail-dovecot` | Dovecot server |
| `imap.port` | `143` | Dovecot IMAP port |
| `imap.security` | `none` | `none`, `starttls` or `tls` |
| `imap.username` | | Dovecot user |
| `imap.password` | | Dovecot password (encrypted) |
| `imap.inbox_folder` | `INBOX` | Folder new email is read from |
| `imap.trash_folder` | `Trash` | Folder deleted email is moved to |
| `imap.folder_scan_frequency` | `12h` | How often the mailbox's folders are read again, for the move dropdowns. They're also read at startup and with "Refresh" in the Move dialog. |
| `smtp.host` | `mail-opensmtp` | SMTP server used to forward email |
| `smtp.port` | `25` | SMTP port |
| `smtp.security` | `none` | `none`, `starttls` or `tls` |
| `smtp.username` | | SMTP user; empty for no authentication |
| `smtp.password` | | SMTP password (encrypted) |
| `smtp.from_address` | | Address forwards are sent from (`imap.username` if empty) |
| `llm.url` | `http://kodiakbear:9931/v1` | Base URL of the OpenAI-compatible llama.cpp API |
| `llm.api_key` | | LLM API key, if needed (encrypted) |
| `llm.model` | `qwen3.8-9b-100k` | Model to request |
| `llm.timeout` | `2m` | How long to wait for the LLM |
| `llm.max_input_chars` | `60000` | Email and page text is cut to this length before it's sent to the LLM. |
| `attachments.path` | `/data/attachments` | Where attachments and bills are saved |
| `attachments.extensions` | `pdf` | Attachment extensions to keep, e.g. `pdf, ics` |
| `events.default_duration` | `1h` | Length of events with no end time |
| `fetch.user_agent` | Chrome on Windows | User agent for fetching pages and files |
| `fetch.timeout` | `30s` | How long to wait for a page or file |
| `bills.pay_link_text` | `Pay now, Pay your bill, …` | Anchor texts that identify a bill's payment link (ignoring case) |
| `bills.bill_link_text` | `View bill, Download bill, …` | Anchor texts that identify a link to download a bill |
| `todo.path` | `/data/todo` | The markdown to-do list that `update_todo_list` writes to; `index.md` must have a `<!-- todo-items:end -->` line, or is created with one |
| `todo.postmaster_url` | (empty) | The address of these pages, e.g. `http://tigerbear:8080/mail`, for links from to-do items back to their email |
| `todo.sync_frequency` | `1h` | How often the to-do list is read for items that are open, in progress or done there |
| `health.pending_limit` | `25` | The status endpoint warns when more emails than this are waiting to be processed |
| `health.min_free_mb` | `1024` | The status endpoint warns when a disk holding attachments, the to-do list or calendars has less free space (MiB) |
| `tracking.frequency` | `1h` | How often tracked emails are looked for in their folder, to notice those moved or deleted (CLARIFICATIONS.md R9) |
| `tracking.window` | `7d` | Only emails received within this period are looked for |

## Types, calendars, prompts and rules

These are also stored in the database and managed on their own pages.
- **Types:** Bill is a system type and can't be deleted.
- **Calendars:** names come from the khal config, and each has a colour and notification periods. Notifications (here and on `create_events` actions) are durations before the event (`4h`, `1d`) or a time of day a number of days before the event's day: `d-1@20:00` is 8pm the day before, `d@8am` 8am on the day. Times are 24-hour or 12-hour (`8pm`, `8:30am`), in `processing.timezone`. Mix them, separated by a comma and a space: `1h, d-1@20:00`.
- **Prompts:** the system prompts (summarise, classify, select_calendar, summarise_page, bill_details, identify_events) can be edited but not deleted. Their placeholders are `{{subject}}`, `{{sender}}`, `{{text}}`, `{{html}}`, `{{page}}`, `{{events}}`, `{{calendars}}` and `{{types}}`.
- **Rules:** see [actions.md](actions.md) for the actions. String conditions ignore case, `regex` included; start a pattern with `(?-i)` to match case.
