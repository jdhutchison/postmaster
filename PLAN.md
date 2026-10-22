# Postmaster: Execution plan

Based on REQUIREMENTS.md and the answers in CLARIFICATIONS.md.

Legend: `[ ]` to do · `[~]` in progress · `[x]` done · `[!]` blocked (see DISCUSSION.md)

**Status:** Plan written and waiting for your go-ahead.

Standing rules:
- Commit after each completed task.
- Log every change to tigerbear or kodiakbear in `changes.md`.
- Write further communication between us to `DISCUSSION.md`.

---

## Phase 0: Project setup

- [ ] **0.1** `git init`, add `.gitignore` and `.editorconfig` (2-space indent, 4-space continuation), and commit the requirement documents.
- [ ] **0.2** Install GraalVM (JDK 25 LTS) and Gradle locally with SDKMAN (user-level). There is no JDK on this machine yet.
- [ ] **0.3** Create the Gradle (Kotlin DSL) Quarkus project:
  - Kotlin, plus Quarkus REST, Qute, Hibernate ORM Panache Kotlin, MariaDB JDBC, Flyway, Scheduler, SmallRye OpenAPI, YAML config and the MCP server (HTTP).
  - MapStruct through kapt.
  - H2, GreenMail, WireMock and Playwright for tests.
- [ ] **0.4** Write a custom `main` that takes the YAML config path as its single argument, plus the startup config: DB, encryption key and khal config path. Add a smoke test that boots the app on H2.
- [ ] **0.5** Add `bin/` scripts for running in dev, running tests, and building the JVM and native packages.

## Phase 1: Data layer

- [ ] **1.1** Flyway migrations for all tables, using `Snake_case` table names and lower snake case columns. The migrations must work on both MariaDB and H2. Tables:
  - `Settings`, `Types`, `Calendars`, `Prompts`
  - `Rules`, `Conditions`, `Rule_actions`
  - `Emails`, `Email_events`, `Email_attachments`, `Email_rule_matches`, `Email_action_results`, `Email_errors`
  - `Processing_runs`
- [ ] **1.2** Seed migrations:
  - default settings
  - the initial types, with `Bill` as a system type
  - the system prompts with fixed keys, including a new `identify_events` prompt
- [ ] **1.3** Entities (`…Entity`), Panache repositories, models where needed, and MapStruct mappers.
- [ ] **1.4** `Crypto` (AES-256-GCM with the `enc:` prefix) and `SettingsService`: typed values, validation, and full and simplified ISO 8601 duration parsing. Unit tests.
- [ ] **1.5** A known test data set and fixtures for the integration tests.

## Phase 2: UI foundation

- [ ] **2.1** The base Qute layout:
  - Dracula and Alucard palettes following the system dark/light mode
  - Atkinson Hyperlegible Next, bundled with the app
  - a frosted-glass hamburger menu
  - toasts (success fades out; errors stay until dismissed)
  - modal and confirm dialogs
  - HTMX, bundled with the app
- [ ] **2.2** A table component:
  - a sticky header
  - resizable columns
  - truncation with a full-text tooltip
  - columns hidden by priority on narrow screens
  - relative timestamps, with the absolute time in a tooltip
- [ ] **2.3** Test harness: RestAssured and Jsoup helpers for the HTML, and Playwright browser test setup.

## Phase 3: Admin pages

Each page gets OpenAPI annotations, HTTP-level tests and Playwright tests.

- [ ] **3.1** Configuration page:
  - click a value to edit it, and it saves when the field loses focus
  - encrypted values are masked
  - values are validated by type
- [ ] **3.2** Types page:
  - a table with edit and delete icons
  - create and edit modals with a colour picker and a hex field
  - soft delete after confirmation
  - system types can't be deleted
- [ ] **3.3** Calendars page:
  - names are chosen from the calendars in the khal config
  - colour, an enable toggle, and validated Durations
  - soft delete
- [ ] **3.4** Prompts page and editor:
  - the textarea fills the viewport
  - a markdown preview toggle
  - a placeholder inserter
  - system prompts can't be deleted
- [ ] **3.5** Rule list pages (classification and action), with enable toggles, edit, and delete after confirmation.
- [ ] **3.6** Create rule and edit rule pages:
  - conditions validated against the Q9 matrix
  - actions with type-specific fields (destination, type, calendar, notifications) and a per-action enable toggle
  - soft delete from the edit page

## Phase 4: Integration helpers

These are reusable, so the action handlers can share them.

- [ ] **4.1** `MailboxClient` (IMAP):
  - list INBOX newest first, and fetch with `BODY.PEEK`
  - parse MIME into text, HTML and attachments
  - move, delete to trash, set flags, and list folders

  Plus an SMTP inline forwarder. Tested with GreenMail.
- [ ] **4.2** `LlmClient`: OpenAI-compatible, JSON mode, timeouts, input truncation, thinking turned off. `PromptRenderer` fills in the `{{placeholder}}` values. Tested with WireMock.
- [ ] **4.3** `HttpFetcher`: headless Chromium `--dump-dom` and curl downloads, using a Chrome-on-Windows user agent. `HtmlParser` (Jsoup): text extraction, and finding links by their anchor text.
- [ ] **4.4** `CalendarClient` (khal):
  - read the calendars from the khal config
  - generate `.ics` files with our UID and alarms, and add them with `khal import`
  - move or delete an event by its vdir file

  Tested with a fake khal and the local khal 0.11.

## Phase 5: Processing pipeline

- [ ] **5.1** Rule engine: condition evaluation following the matrix, priority ordering, and case-insensitive matching.
- [ ] **5.2** Action framework:
  - an `ActionHandler` interface and a registry
  - a processing context
  - the fixed execution order, with move/delete last and conflict handling
  - recording each action's result
- [ ] **5.3** Action handlers:
  - `set_type`, `set_reply_needed`, `set_action_needed` (new), `skip_processing`
  - `mark_read`, `mark_unread`, `move`, `delete`, `forward_email`
  - `download_attachments`, `fetch_link_contents`, `check_for_events`, `create_events`
- [ ] **5.4** `process_bill`:
  - extract the amount and due date with regexes
  - find the pay and bill links in the HTML
  - fall back to the LLM when details are missing
  - add a reminder event to the action's calendar
  - download the bill
- [ ] **5.5** Pipeline orchestrator. Steps:
  1. fetch unprocessed email within the lookback window
  2. classify
  3. LLM phase
  4. action rules
  5. record the status, errors and rule matches

  Also supports reprocessing an email.
- [ ] **5.6** Scheduler: the interval comes from the settings, only one run at a time, each run is recorded in `Processing_runs`, and there is a "Run now" trigger. End-to-end tests with GreenMail, WireMock and the fake khal.

## Phase 6: Email UI and dashboard

- [ ] **6.1** Emails list:
  - the 30 most recent by default
  - filters: date range, status, type, sender, response/action required, errors
  - pages of 50
  - counts, and red highlighting for rows with errors
- [ ] **6.2** Email detail:
  - collapsible sections for the full text and the linked page
  - events coloured by calendar, with a dialog to change an event's calendar
  - the matching rules and the actions they ran
  - delete, move and mark unread buttons
  - attachment downloads and previews
  - clearing action required
- [ ] **6.3** Dashboard: last and next run, counts, and "Run now".

## Phase 7: MCP server

- [ ] **7.1** MCP tools over Streamable HTTP at `/mcp`:
  - search and get emails, set type, move, mark read/unread, delete, forward
  - list types, folders and calendars
  - change an event's calendar
  - set or clear the flags, and reprocess an email

  Tested with an MCP client over HTTP.

## Phase 8: Packaging

- [ ] **8.1** Generate `docs/openapi.yml` at build time from the annotations.
- [ ] **8.2** A multi-stage `Dockerfile`:
  - a GraalVM native build stage
  - a Debian slim runtime with khal, curl and Chromium
  - a non-root user in the `calendars` group
  - mounts for the khal config, calendar data, attachments and the YAML config

  Verify a JVM build and an x86 native build locally.
- [ ] **8.3** A sample `config/postmaster.yml` and the `bin/` build and deploy scripts.

## Phase 9: Deployment (in `/data/code/ansible`)

- [ ] **9.1** Add the `postmaster` service user to `group_vars/all.yaml`, and create `config/postmaster/`:
  - `docker-compose.yml` on the `services` and `mariadb` networks
  - `postmaster.service`
  - `nginx-include.conf` for `/mail`, with basic auth from `/var/www/.htpasswd`
  - the app config template
- [ ] **9.2** `plays/other/postmaster.yml`:
  - the service user and groups
  - the database (with `create-mysql-db.yml`) and directories
  - build the image on tigerbear, then the systemd unit and nginx include
  - register the MCP server in the Hermes `postmaster` profile (the triage job is left alone)
- [ ] **9.3** In the mail stack's `mbsyncrc`, sync Trash by removing `!"Trash"` from the `Pattern`.
- [ ] **9.4** Log every change in `changes.md`. **I'll ask before running anything against tigerbear.**

## Phase 10: Documentation and final checks

- [ ] **10.1** `docs/`: architecture, running and testing, configuration reference, deployment, MCP tools, and how to add a new action type.
- [ ] **10.2** Run all tests, build the native image, review the code, and finish updating this plan.
