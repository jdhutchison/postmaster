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

A run lists INBOX messages received within `processing.lookback`, newest first, and processes each one not yet in the database (matched by Message-ID), each in its own transaction (CLARIFICATIONS.md Q14). For each email:

1. **Record.** The parsed message is stored, with the attachments whose extensions are in `attachments.extensions`.
2. **Classification.** Enabled classification rules are tested in priority order (highest first; ties go to the older rule). A rule matches when all its conditions match (Q21). Only the first matching rule applies. Its record-only actions (`set_type`, `set_reply_needed`, `skip_processing`) run at once; the rest wait (Q12).
3. **LLM.** An email still without a type goes to the `classify` prompt. Every email then goes to `summarise`, which gives the summary, link, response and action flags, and events (R1). After that, the classification rule's `fetch_link_contents` and `check_for_events` run.
4. **Action rules.** Every enabled action rule that matches (with the facts as they are after the LLM) adds its actions to the queue.
5. **Actions.** The queue runs in a fixed order of stages: record, gather (fetch, events, downloads, bills), forward, read state, then move or delete last. Only one move/delete and one read/unread runs; the highest-priority rule wins and the others are recorded as conflicts.
6. **Finish.** The status is `processed`, `skipped` (by `skip_processing`) or `failed`. Every rule match, action result and error is recorded against the email.

Errors from the outside world (IMAP, the LLM, khal, fetching) are recorded against the email, and processing carries on. Bills never need action (Q13): `EmailEntity` enforces that whenever the type or the flag changes.

## User interface

Server-rendered Qute templates with HTMX for partial updates, and one small script (`assets/js/postmaster.js`) for menus, dialogs, toasts and tables. Everything is served by the application under `/mail`; a Content-Security-Policy forbids anything external. The layout follows the system's light or dark mode (Alucard or Dracula) and uses Atkinson Hyperlegible Next. `docs/dev/contracts.md` records every route, form field and `data-testid`.

## MCP server

`McpTools` exposes email search, details and actions over Streamable HTTP at `/mail/mcp`, sharing `EmailService` with the UI. See [mcp.md](mcp.md).

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
