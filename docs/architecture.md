# Postmaster architecture

Postmaster reads a Dovecot inbox over IMAP, classifies each new email with rules and an LLM, and acts on it: setting its type, filing it, adding events to calendars, saving bills, forwarding it and so on. It has a web UI and an MCP server, for Hermes.

It's a Kotlin [Quarkus](https://quarkus.io) application, built with Gradle and compiled to a GraalVM native executable for production. Data is in MariaDB, and the schema is managed by Flyway at startup.

```
            ┌───────────── Postmaster ─────────────────────────────────────┐
 Dovecot ◄──┤ MailboxClient (IMAP, SMTP forward)                           │
 SMTP    ◄──┤                                                              │
 llama.cpp◄─┤ LlmClient ◄── PromptRenderer ◄── Prompts                     │
 khal    ◄──┤ CalendarClient (.ics + khal import, vdir moves)              │
 web     ◄──┤ HttpFetcher (headless Chromium, curl) + HtmlParser           │
            │                                                              │
            │ ProcessingScheduler ─► ProcessingService                     │
            │     fetch ─► classification rules ─► LLM ─► action rules     │
            │            RuleEngine        ActionExecutor ─► ActionHandlers│
            │                                                              │
            │ Controllers (Qute + HTMX pages)    McpTools (/mail/mcp)      │
            │            └──────────► EmailService ◄──────┘                │
            │ Repositories (Panache) ─► MariaDB                            │
            └──────────────────────────────────────────────────────────────┘
```

## Packages

All code is under `sh.hutch.postmaster`. The requirements ask for controllers, services and repositories. The other packages were agreed in CLARIFICATIONS.md Q22.

| Package | Contents |
|---|---|
| `controllers` | Presentation. JAX-RS resources rendering Qute templates (the pages and HTMX fragments), the MCP tools (`McpTools`), toasts, flash messages and the OpenAPI filter. |
| `services` | Business logic and the integration helpers: `ProcessingService` (the pipeline), `RuleEngine`, `EmailService` (manual actions for the UI and MCP), `DashboardService`, the admin services, `SettingsService`, `Crypto`, `MailboxClient`, `LlmClient`, `PromptRenderer`, `HttpFetcher`, `HtmlParser` and `CalendarClient`. |
| `actions` | The action framework: `ActionHandler`, `ActionRegistry`, `ActionExecutor`, `ProcessingContext` and one handler per action type (see [actions.md](actions.md)). |
| `repositories` | Panache repositories and queries. |
| `entities` | JPA entities (`…Entity`), one per table, plus the converters that store enums as codes. |
| `models` | Enums, form inputs, view models for pages and MCP, and the JSON shapes exchanged with the LLM. |
| `mappers` | MapStruct mappers. |
| `config` | The startup configuration (`PostmasterConfig`) and startup checks. |
| `util` | Small helpers: ISO 8601 durations, relative times, names, colours, process running. |

Pages and MCP results use view models (`models/EmailViews.kt`, `Dashboard.kt`, the rule summaries). They're built inside the service's transaction, because Qute renders after the resource method returns, when lazy associations can no longer be loaded. Simple mappings are written in Kotlin in the services; MapStruct is used for the settings.

## Processing an email

`ProcessingScheduler` checks every minute whether a run is due (`processing.frequency` after the last run started), so a frequency change applies without a restart. "Run now" on the dashboard starts a run straight away. Only one run happens at a time, and each is recorded in `Processing_runs`.

`BackgroundJobs` runs three other jobs, each soon after startup and then at its own interval, checked every minute (CLARIFICATIONS.md R9):
- `FolderService.scan()` reads the mailbox's folders into the `Folders` table every `imap.folder_scan_frequency`, for the move dropdowns.
- `TrackingService.check()` looks for tracked emails received within `tracking.window` in their folder, then in the trash folder, every `tracking.frequency`. Emails that aren't there become `moved` or `deleted`, and `EmailService` refuses to act on any email that isn't tracked.
- `TodoListService.sync()` reads the to-do list every `todo.sync_frequency` and brings in each item's status (open, in progress or done), keeping `index.md` and the item's front matter in step.

A run first marks done the action items ticked off in the to-do list, then carries on with any email left part-way, then lists the INBOX messages received within `processing.lookback` of the latest successful run, newest first, and processes each one not yet in the database (matched by Message-ID; CLARIFICATIONS.md Q14).

**No email is lost track of (R6).** A new email is saved before anything is done with it, and saved again after each step, each step in a transaction of its own. If processing stops part-way (a crash, a restart, the database going away), the email stays `pending` with the last step it saved, and the next run carries on from there. The steps:

1. **Record.** The parsed message is stored, with the attachments whose extensions are in `attachments.extensions`.
2. **Classification.** Enabled classification rules are tested in priority order (highest first; ties go to the older rule). A rule matches when all its conditions match, or any one of them for a rule set to match any (Q21, R8). Only the first matching rule applies. Its record-only actions (`set_type`, `set_reply_needed`, `skip_processing`) run at once; the rest wait (Q12).
3. **LLM.** An email still without a type goes to the `classify` prompt. Every email then goes to `summarise`, which gives the summary, link, response flag, action items and events (R1, R5). No transaction is open while the LLM answers. If the LLM can't be reached, the email waits for the next run before any action runs, up to five attempts. After that, the classification rule's `fetch_link_contents` and `check_for_events` run.
4. **Action rules.** Every enabled action rule that matches (with the facts as they are after the LLM) is recorded.
5. **Actions**, one at a time, each saved as it finishes, in a fixed order of stages: record, gather (fetch, events, downloads, bills, to-do list), forward, read state, then move or delete last. Only one move/delete and one read/unread runs; the highest-priority rule wins and the others are recorded as conflicts.
6. **Finish.** The status is `processed`, `skipped` (by `skip_processing`) or `failed`. Every rule match, action result and error is recorded against the email.

**Carrying on safely.** The recorded action results say which actions have run, so none runs twice. Actions are written to be safe to repeat if they were cut off part-way: a move notices the message is already in its destination, calendar events have UIDs made from the email and event so they aren't added twice, and a to-do item's name is saved before its files are written. `forward_email` can't be made safe to repeat, so it's recorded as started before it sends; if it's found started later, it's recorded as failed ("check before running it again") rather than sent twice. An email that fails five passes in a row is marked failed for you to reprocess.

**Other safeguards.** The LLM client tries again after 2 and 10 seconds when the LLM can't be reached or is busy, and the IMAP client tries once more to connect. When the application is told to stop, it lets the current step finish (up to 20 seconds) and starts no new one.

Errors from the outside world (IMAP, the LLM, khal, fetching, the to-do list), and settings that can't be read, are recorded against the email, and processing carries on. `SettingsService` and `PromptRenderer` don't roll back the step's transaction when they throw, so a failed action never loses the work already done. Bills never have action items (Q13): `EmailEntity` enforces that whenever the type or the items change.

**Finding the message again.** Each email stores the message's folder, UID and the folder's UIDVALIDITY. Mailbox actions use the UID only while the folder's UIDVALIDITY is unchanged and a message has it; otherwise they find the message by its Message-ID, so a rebuilt mailbox can't make an action hit another message.

**Reprocessing** replaces the email's rule matches, results, errors and summary, and runs the steps again. Events already added to a calendar and action items already in the to-do list are kept; the LLM's events and items are added again except those the email already has. One pass works on an email at a time.

## User interface

Server-rendered Qute templates with HTMX for partial updates, and one small script (`assets/js/postmaster.js`) for menus, dialogs, toasts and tables. Everything is served by the application under `/mail`; a Content-Security-Policy forbids anything external. Because nginx's basic auth goes with any request from the browser, POST, PUT and DELETE requests whose `Sec-Fetch-Site` is anything but `same-origin` or `none` are refused with 403 (`CrossSiteRequestFilter`), so another site can't make changes. Clients that don't send the header, such as MCP clients and curl, aren't affected. The layout follows the system's light or dark mode (the Water.css palettes) and uses Atkinson Hyperlegible Next. `docs/dev/contracts.md` records every route, form field and `data-testid`.

## MCP server

`McpTools` exposes email search, details and actions over Streamable HTTP at `/mail/mcp`, sharing `EmailService` with the UI. See [mcp.md](mcp.md).

## Status

`HealthController` serves `/mail/health` and `/mail/status`. `HealthService` runs its checks in parallel (5 seconds each): the database, Dovecot, SMTP, the LLM, the encrypted settings, khal, Chromium, curl, the attachments and to-do folders, the to-do list's `index.md` and free disk space, plus a processing check worked out from the run history. It reports the overall status: `OK`, `DEGRADED` (a check other than the database or Dovecot is down or warns) or `DOWN` (the database or Dovecot is down, HTTP 503). It also reports the version, commit and build time (`BuildInfo`, from the `build-info.properties` that Gradle writes), the uptime and what processing is doing. A client asking for `application/health+json` gets the IETF draft's format instead. See [running.md](running.md#status) and CLARIFICATIONS.md R7.

## Logging

Everything is logged to stdout in every profile, so Docker collects it (CLARIFICATIONS.md R3). The format is set by `quarkus.log.console.format` and ends each line's prefix with the MDC (`%X`). `util.Mdc.with(key to value, …) { … }` sets keys for a block of code and restores what was there before, so each key is removed when that piece of work ends.

| Key | Set while | By |
|---|---|---|
| `run` | a processing run | `ProcessingService` |
| `message_id` | a message is fetched and stored | `ProcessingService.process` |
| `email` | an email is processed, reprocessed or acted on by hand | `ProcessingService`, `EmailService` |
| `phase` | `classification`, `llm` or `action` | `ProcessingService.pipeline` |
| `rule` | a rule matches, or one of its actions runs | `ProcessingService`, `ActionExecutor` |
| `action` | an action runs | `ActionExecutor`, `EmailService` |
| `source` | a manual action runs (`ui` or `mcp`) | `EmailService` |

What's logged:
- each run's start and end, with its counts
- each email processed, with its result
- each rule that matches
- each action's outcome
- every error recorded against an email
- manual actions

## Configuration

Only what's needed to start (the database, the encryption key and the khal config) is in the YAML file passed as the single command line argument. Everything else is in the `Settings` table and edited on the Configuration page; passwords are stored AES-256-GCM encrypted with an `enc:` prefix. See [configuration.md](configuration.md).
