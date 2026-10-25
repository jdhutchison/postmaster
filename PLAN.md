# Postmaster: Execution plan

Based on REQUIREMENTS.md and the answers in CLARIFICATIONS.md.

Legend:
- `[ ]` to do · `[~]` in progress · `[x]` done · `[!]` blocked (see DISCUSSION.md)
- Each task carries two markers:
  - `build:` is updated by the builder agent
  - `tests:` is updated by the tester agent
- Marker values are `todo`, `doing`, `done` or `n/a`. A task's checkbox is ticked `[x]` only when both markers are done or n/a and the coordinator has assessed the tester's full-suite results (from 2026-10-24, the coordinator runs the suite itself only when there's a stated need). From Phase 5 on, see the single-agent arrangement: tasks are ticked when the build side is done and verified, and `tests: todo` means deferred.

**Status:** Every phase is complete. Development moved to a macOS (arm64) machine on 2026-10-24 and back to Linux on 2026-10-25, when Postmaster was deployed to tigerbear (Phase 9) at your request.

Standing rules:
- Commit after each completed task.
- Log every change to tigerbear or kodiakbear in `changes.md`.
- Write further communication between us to `DISCUSSION.md`.

## Working arrangement (from 2026-10-24): single agent

To save tokens, the tester agent has stopped. Testing restarts once everything is built. Until then:
- **One agent.** The builder is the only agent. It works through Phases 5–9 and 10.1 continuously, sending the coordinator a short status at the end of each phase. It stops only for your decisions or anything touching tigerbear or kodiakbear. The Ansible play is never run against a host without your go-ahead.
- **Progress doesn't depend on the tester.** A task is ticked `[x]` once its build side is done and verified: it compiles, its behaviour has been checked with throwaway checks, and the existing suite still passes at the end of the phase. The builder ticks the box itself.
- **Deferred tests.** `tests: todo` now means "tests deferred until testing restarts".
- **Contracts.** The builder keeps `docs/dev/contracts.md` up to date so the tester can catch up later.

The earlier two-agent arrangement below applied to Phases 0–4.

## Working arrangement (from 2026-10-22)

Two agents work in parallel in this working tree, coordinated by the main Claude session (the coordinator).

- **Builder.** Owns all production work:
  - `src/main/**` and the Gradle build files
  - `bin/`, the `Dockerfile` and `docs/` (except `docs/testing.md`)
  - the Ansible files in `/data/code/ansible`

  The builder updates the `build:` markers.
- **Tester.** Owns all tests: `src/test/**`, covering:
  - unit, HTTP-level integration and Playwright browser tests
  - the GreenMail, WireMock and fake khal/Chromium stubs
  - the known data set
  - `docs/testing.md`

  The tester updates the `tests:` markers. It writes tests from REQUIREMENTS.md, CLARIFICATIONS.md and the contracts, not from the builder's code.
- **Contracts.** Before implementing each feature, the builder records in `docs/dev/contracts.md`:
  - routes and form field names
  - element ids and `data-testid`s, and HTMX endpoints
  - MCP tool signatures
  - test seams, such as configurable binary paths

  The tester may propose changes to the contracts.
- **Communication.** The agents message each other directly. Anything only you can decide goes through the coordinator to you, and answers are recorded in CLARIFICATIONS.md.
- **Requirements win.** If code and tests disagree, REQUIREMENTS.md and CLARIFICATIONS.md decide. Neither agent changes or weakens the other's work to get a pass.
- **No build collisions.** The tester builds into `build-tests/` (`POSTMASTER_BUILD_DIR=build-tests`, `--project-cache-dir .gradle-tests`), and Quarkus tests use a random HTTP port.
- **Commits.** Each agent commits only its own paths (`git add <paths> && git commit -m "…" -- <paths>`).
- **Checkpoints.** Both agents stop at the end of each phase (or when blocked on a question) and report. The coordinator then assesses the tester's full-suite results (its JUnit reports in `build-tests/`), updates this plan and resumes them. The coordinator re-runs the suite only when there's a need, such as results that are missing, stale or contradictory, and says why.

---

## Phase 0: Project setup

- [x] **0.1** `build: done` `tests: n/a` *(coordinator)* `git init`, add `.gitignore` and `.editorconfig` (2-space indent, 4-space continuation), and commit the requirement documents.
- [x] **0.2** `build: done` `tests: n/a` *(coordinator)* Install GraalVM (JDK 25 LTS) and Gradle locally with SDKMAN (user-level). There is no JDK on this machine yet.
- [x] **0.3** `build: done` `tests: done` Create the Gradle (Kotlin DSL) Quarkus project:
  - Kotlin, plus Quarkus REST, Qute, Hibernate ORM Panache Kotlin, MariaDB JDBC, Flyway, Scheduler, SmallRye OpenAPI, YAML config and the MCP server (HTTP).
  - MapStruct through kapt.
  - H2, GreenMail, WireMock and Playwright for tests.
- [x] **0.4** `build: done` `tests: done` Write a custom `main` that takes the YAML config path as its single argument, plus the startup config: DB, encryption key and khal config path. Add a smoke test that boots the app on H2.
- [x] **0.5** `build: done` `tests: n/a` Add `bin/` scripts for running in dev, running tests, and building the JVM and native packages.

## Phase 1: Data layer

- [x] **1.1** `build: done` `tests: done` Flyway migrations for all tables, using `Snake_case` table names and lower snake case columns. The migrations must work on both MariaDB and H2. Tables:
  - `Settings`, `Types`, `Calendars`, `Prompts`
  - `Rules`, `Conditions`, `Rule_actions`
  - `Emails`, `Email_events`, `Email_attachments`, `Email_rule_matches`, `Email_action_results`, `Email_errors`
  - `Processing_runs`
- [x] **1.2** `build: done` `tests: done` Seed migrations:
  - default settings
  - the initial types, with `Bill` as a system type
  - the system prompts with fixed keys, including a new `identify_events` prompt and the Summarise prompt extended to return `action_required` and `action` (CLARIFICATIONS R1 and D19)
- [x] **1.3** `build: done` `tests: done` Entities (`…Entity`), Panache repositories, models where needed, and MapStruct mappers.
- [x] **1.4** `build: done` `tests: done` `Crypto` (AES-256-GCM with the `enc:` prefix) and `SettingsService`: typed values, validation, and full and simplified ISO 8601 duration parsing. Unit tests.
- [x] **1.5** `build: done` `tests: done` A known test data set and fixtures for the integration tests.

## Phase 2: UI foundation

- [x] **2.1** `build: done` `tests: done` The base Qute layout:
  - Dracula and Alucard palettes following the system dark/light mode
  - Atkinson Hyperlegible Next, bundled with the app
  - a frosted-glass hamburger menu
  - toasts (success fades out; errors stay until dismissed)
  - modal and confirm dialogs
  - HTMX, bundled with the app
- [x] **2.2** `build: done` `tests: done` A table component:
  - a sticky header
  - resizable columns
  - truncation with a full-text tooltip
  - columns hidden by priority on narrow screens
  - relative timestamps, with the absolute time in a tooltip
- [x] **2.3** `build: n/a` `tests: done` Test harness: RestAssured and Jsoup helpers for the HTML, and Playwright browser test setup.

## Phase 3: Admin pages

Each page gets OpenAPI annotations, HTTP-level tests and Playwright tests.

- [x] **3.1** `build: done` `tests: done` Configuration page:
  - click a value to edit it, and it saves when the field loses focus
  - encrypted values are masked
  - values are validated by type
- [x] **3.2** `build: done` `tests: done` Types page:
  - a table with edit and delete icons
  - create and edit modals with a colour picker and a hex field
  - soft delete after confirmation
  - system types can't be deleted
- [x] **3.3** `build: done` `tests: done` Calendars page:
  - names are chosen from the calendars in the khal config
  - colour, an enable toggle, and validated Durations
  - soft delete
- [x] **3.4** `build: done` `tests: done` Prompts page and editor:
  - the textarea fills the viewport
  - a markdown preview toggle
  - a placeholder inserter
  - system prompts can't be deleted
- [x] **3.5** `build: done` `tests: done` Rule list pages (classification and action), with enable toggles, edit, and delete after confirmation.
- [x] **3.6** `build: done` `tests: done` Create rule and edit rule pages:
  - conditions validated against the Q9 matrix
  - actions with type-specific fields (destination, type, calendar, notifications) and a per-action enable toggle
  - soft delete from the edit page

## Phase 4: Integration helpers

These are reusable, so the action handlers can share them.

- [x] **4.1** `build: done` `tests: done` `MailboxClient` (IMAP):
  - list INBOX newest first, and fetch with `BODY.PEEK`
  - parse MIME into text, HTML and attachments
  - move, delete to trash, set flags, and list folders

  Plus an SMTP inline forwarder. Tested with GreenMail.
- [x] **4.2** `build: done` `tests: done` `LlmClient`: OpenAI-compatible, JSON mode, timeouts, input truncation, thinking turned off. `PromptRenderer` fills in the `{{placeholder}}` values. Tested with WireMock.
- [x] **4.3** `build: done` `tests: done` `HttpFetcher`: headless Chromium `--dump-dom` and curl downloads, using a Chrome-on-Windows user agent. `HtmlParser` (Jsoup): text extraction, and finding links by their anchor text.
- [x] **4.4** `build: done` `tests: done` `CalendarClient` (khal):
  - read the calendars from the khal config
  - generate `.ics` files with our UID and alarms, and add them with `khal import`
  - move or delete an event by its vdir file

  Tested with a fake khal and the local khal 0.11.

## Phase 5: Processing pipeline

- [x] **5.1** `build: done` `tests: done` Rule engine: condition evaluation following the matrix, priority ordering, and case-insensitive matching.
- [x] **5.2** `build: done` `tests: done` Action framework:
  - an `ActionHandler` interface and a registry
  - a processing context
  - the fixed execution order, with move/delete last and conflict handling
  - recording each action's result
- [x] **5.3** `build: done` `tests: done` Action handlers:
  - `set_type`, `set_reply_needed`, `skip_processing`
  - `mark_read`, `mark_unread`, `move`, `delete`, `forward_email`
  - `download_attachments`, `fetch_link_contents`, `check_for_events`, `create_events`
- [x] **5.4** `build: done` `tests: done` `process_bill`:
  - extract the amount and due date with regexes
  - find the pay and bill links in the HTML
  - fall back to the LLM when details are missing
  - add a reminder event to the action's calendar
  - download the bill
- [x] **5.5** `build: done` `tests: done` Pipeline orchestrator. Steps:
  1. fetch unprocessed email within the lookback window
  2. classify
  3. LLM phase
  4. action rules
  5. record the status, errors and rule matches

  `action_required` and `action_description` come from the Summarise result and are always false/empty for `Bill` emails (Q13 revision).

  Also supports reprocessing an email.
- [x] **5.6** `build: done` `tests: done` Scheduler: the interval comes from the settings, only one run at a time, each run is recorded in `Processing_runs`, and there is a "Run now" trigger. End-to-end tests with GreenMail, WireMock and the fake khal.

## Phase 6: Email UI and dashboard

- [x] **6.1** `build: done` `tests: done` (HTTP level; browser tests to do) Emails list:
  - the 30 most recent by default
  - filters: date range, status, type, sender, response/action required, errors
  - pages of 50
  - counts, and red highlighting for rows with errors
- [x] **6.2** `build: done` `tests: done` (HTTP level; browser tests to do) Email detail:
  - collapsible sections for the full text and the linked page
  - events coloured by calendar, with a dialog to change an event's calendar
  - the matching rules and the actions they ran
  - delete, move and mark unread buttons
  - attachment downloads and previews
  - clearing action required
- [x] **6.3** `build: done` `tests: done` (HTTP level; browser tests to do) Dashboard: last and next run, counts, and "Run now".

## Phase 7: MCP server

- [x] **7.1** `build: done` `tests: done` MCP tools over Streamable HTTP at `/mcp`:
  - search and get emails, set type, move, mark read/unread, delete, forward
  - list types, folders and calendars
  - change an event's calendar
  - set or clear the flags (setting the type to `Bill` clears `action_required`), and reprocess an email

  Tested with an MCP client over HTTP.

## Phase 8: Packaging

- [x] **8.1** `build: done` `tests: n/a` Generate `docs/openapi.yml` at build time from the annotations.
- [x] **8.2** `build: done` `tests: n/a` A multi-stage `Dockerfile`:
  - a GraalVM native build stage
  - a Debian slim runtime with khal, curl and Chromium
  - a non-root user in the `calendars` group
  - mounts for the khal config, calendar data, attachments and the YAML config

  Verify a JVM build and an x86 native build locally. Verified instead by the arm64 native build on tigerbear (2026-10-25); Docker isn't usable on either dev machine. That build found and fixed: `version.properties` copied too late, `GIT_COMMIT` not reaching the build stages, and Jakarta Mail's service files missing from the native image.
- [x] **8.3** `build: done` `tests: n/a` A sample `config/postmaster.yml` and the `bin/` build and deploy scripts.

## Additions (2026-10-24)

- [x] **A.1** `build: done` `tests: done` Dev mode on an in-memory database with fake data (CLARIFICATIONS.md R2).
- [x] **A.2** `build: done` `tests: done` Logging to stdout with MDC context: run, email, phase, rule, action and source (CLARIFICATIONS.md R3).
- [x] **A.3** `build: done` `tests: done` Action items and the `update_todo_list` action, writing to and reading from a markdown to-do list (CLARIFICATIONS.md R5).
- [x] **A.4** `build: done` `tests: done` Processing in saved, resumable steps, so no email is lost track of; actions safe to repeat; LLM and IMAP retries; lookback from the last successful run; graceful shutdown (CLARIFICATIONS.md R6).
- [x] **A.5** `build: done` `tests: done` Status endpoint at `/mail/health` and `/mail/status`, and build-number versions (CLARIFICATIONS.md R7).
- [x] **A.6** `build: done` `tests: done` More status checks (settings, processing, khal, Chromium, curl, folders, disk space), the build's commit, and the IETF draft's `application/health+json` format (CLARIFICATIONS.md R7).

## Phase 9: Deployment (in `/data/code/ansible`)

- [x] **9.1** `build: done` `tests: n/a` Add the `postmaster` service user to `group_vars/all.yaml`, and create `config/postmaster/`:
  - `docker-compose.yml` on the `services` and `mariadb` networks
  - `postmaster.service`
  - `nginx-include.conf` for `/mail`, with basic auth from `/var/www/.htpasswd`
  - the app config template

  The khal configuration is tigerbear's own `/drive/sys/config/khal.ini`, mounted read only, with the calendars at `~/.calendars`.
- [x] **9.2** `build: done` `tests: n/a` `plays/other/postmaster.yml`:
  - the service user and groups
  - the database (with `create-mysql-db.yml`) and directories
  - build the image on tigerbear, then the systemd unit and nginx include
  - register the MCP server in the Hermes `postmaster` profile (the triage job is left alone)
- [x] **9.3** `build: done` `tests: n/a` In the mail stack's `mbsyncrc`, sync Trash by removing `!"Trash"` from the `Pattern`.
- [x] **9.4** `build: done` `tests: n/a` Log every change in `changes.md`. Deployed on 2026-10-25 at your request, with `ansible-playbook -i tigerbear, -e @group_vars/all.yaml plays/other/postmaster.yml` (`tmpinv.yml`'s key path is the Mac's).

## Phase 10: Documentation and final checks

- [x] **10.1** `build: done` `tests: n/a` `docs/`: architecture, running and testing, configuration reference, deployment, MCP tools, and how to add a new action type.
- [x] **10.2** `build: n/a` `tests: n/a` *(coordinator)* Run all tests, build the native image, review the code, and finish updating this plan. Done 2026-10-25: 635 tests pass; the native image builds on macOS (arm64, 93 MiB); a review's six findings are fixed (UIDVALIDITY, rollbacks from settings errors, cross-site requests, events on reprocess, reused to-do names, concurrent reprocessing). 8.2 is still unverified because Docker doesn't run on this machine.
