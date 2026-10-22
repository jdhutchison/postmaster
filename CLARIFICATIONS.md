# Postmaster: Clarifications

This file records questions about REQUIREMENTS.md, suggestions, and the answers given.

- **Q** items are questions put to you, each with my proposed answer.
- **D** items are smaller decisions I'll adopt as proposed unless you object.
- **S** items are technical suggestions.

---

## Notes on the requirements

These are things I noticed while reading. They don't need an answer unless stated.

- **N1. Stray number at REQUIREMENTS.md line 195.** Under "Creating a new rule" there is a 16 digit number on its own line. It looks like it may have been pasted by accident and could be a payment card number. See Q20. *(Removed on 2026-10-22, as agreed in Q20.)*
- **N2. Two sentences are cut off.** "Tables can reduce the number of columns to be reactive, with the…" (UI style) and "Once the email has been classified and all preliminary actions have been…" (processing). See D12 and Q12.
- **N3. The condition/match-type table lost its blank cells** when it was converted to text, so you can't tell which attribute each `Y` belongs to. See Q9.
- **N4. The schema and UI sections disagree.**
  - The Conditions schema lists attributes `sender, subject, events, attachments, type` and match types `is, contains, starts_with, regex, is_present, has_any`.
  - The UI section lists attributes `subject, sender, message, events, attachments, bill_url, type, link` and match types `is, contains, starts_with, regex, has, is_present`.
  - I'll go with the UI lists, which include everything in the schema lists.
- **N5. Interpretations of likely typos:**
  - "responsible" → responsive.
  - "model, flat" → modern, flat.
  - The "colour of a calendar" item in the Types section → the *type's* colour.
  - "Atkinson Hyperledgible Next" → Atkinson Hyperlegible Next.
  - "Darcula" → see Q18.
- **N6. The khal documentation link is for v0.3 (2015).** I'll target whichever current khal version is packaged in the container's base image.
- **N7. What I learned from the existing setup in `/data/code/ansible`:**
  - tigerbear runs **MariaDB** in Docker (container `mariadb`, network `mariadb`), not MySQL.
  - Dovecot is `mail-dovecot:143` on the `services` network, with no TLS. It is a **two-way mbsync mirror of Proton**, so moves, flags and deletes made here show up in Proton. `Trash`, `Spam` and `Archive` are **not** synced.
  - nginx runs in Docker on the `services` network. Service includes go in `/drive/services/nginx/config/includes/services/`.
  - Hermes already has a `postmaster` profile with an `email-triage` cron job (7am, noon and 6pm, Monday to Saturday) that moves and stars INBOX mail via `mail-inbox`.
  - llama.cpp runs on kodiakbear (`:9931`, OpenAI-compatible, models chosen by name) and is reached over Tailscale.
  - Calendars: `khal.ini` is at `/drive/sys/config/khal.ini` and data is in `/drive/sys/data/calendars` (group `calendars`). vdirsyncer runs every 2 minutes from root's crontab.
  - tigerbear is arm64. This dev machine is x86_64.

---

## Questions

### Infrastructure

**Q1. Database: MariaDB or MySQL?**
tigerbear already runs MariaDB, and the requirements say MySQL.
*Proposed:* use the existing MariaDB with the MariaDB JDBC driver. Create the database and user with the repo's existing `tasks/create-mysql-db.yml`.
*Answer (2026-10-22):* Use the existing MariaDB, as proposed.

**Q2. Where should the arm64 native image be built?**
Native image compilation for arm64 on this x86_64 machine under QEMU emulation is very slow (it can take hours).
*Proposed:* use a multi-stage Dockerfile and build the image on tigerbear itself during deployment.
*Answer (2026-10-22):* Build on tigerbear, as proposed.

**Q3. Where should the Ansible playbook live?**
*Proposed:* in `/data/code/ansible`, following its conventions:
- the play in `plays/other/postmaster.yml` and its files in `config/postmaster/`
- a `postmaster` service user added to `group_vars/all.yaml`
- Docker compose plus a systemd unit, modelled on the Hermes play
- the nginx include at `/mail`

*Answer (2026-10-22):* In the ansible repo, as proposed.

**Q4. Which LLM endpoint?**
The options are llama.cpp on kodiakbear directly, or the LiteLLM in the Hermes stack. LiteLLM's default `classifier-router` can send traffic to OpenRouter, which is not appropriate for email content.
*Proposed:* llama.cpp directly (`http://kodiakbear:9931/v1`). The model name is a setting.
*Answer (2026-10-22):* llama.cpp directly, as proposed.

### Mail and Hermes

**Q5. How should Postmaster coexist with the Hermes `email-triage` job?**
Both would act on the same INBOX. If Hermes files a message before Postmaster sees it, Postmaster never processes it.
*Proposed:* the Ansible play registers Postmaster's MCP server in the Hermes `postmaster` profile and leaves the triage job untouched. You can retire the job later.
*Answer (2026-10-22):* Register the MCP server and keep the triage job, as proposed.

**Q6. What should "delete" do, given that Trash isn't synced to Proton?**
Moving a message to Dovecot's `Trash` makes it disappear from Proton's INBOX, but it won't appear in Proton's Trash.
*Proposed:* move it to the trash folder as specified. The trash folder name is a setting.
*Answer (2026-10-22):* Move to the trash folder (a setting, default `Trash`). **Also** change the mail stack's `mbsyncrc` so Trash syncs to Proton, by removing `!"Trash"` from the `Pattern`. This is a tigerbear change, logged in changes.md.

**Q7. How should `forward_email` send mail?**
No SMTP server is listed in the settings.
*Proposed:* add SMTP settings (host, port, user, password and from address), defaulting to `mail-opensmtp:25` on the `services` network. Send an inline forward (quoted body plus the original attachments).
*Answer (2026-10-22):* SMTP settings and an inline forward, as proposed.

**Q8. Where should the basic auth credentials for `/mail` come from?**
*Proposed:* a dedicated htpasswd file (`postmaster.htpasswd`) that the play generates from a user and password you pass as play variables.
*Answer (2026-10-22):* Reuse the existing `/var/www/.htpasswd`.

### Rules

**Q9. Which match types apply to which attributes?**
This is my reconstruction of the table:

| match type \ attribute | subject | sender | message | events | attachments | bill_url | type | link |
|---|---|---|---|---|---|---|---|---|
| is | Y | Y | | | | Y | Y | Y |
| contains | Y | Y | Y | | | | | Y |
| starts_with | Y | Y | Y | | | Y | | Y |
| regex | Y | Y | Y | Y | Y | Y | Y | Y |
| has | | | | Y | Y | | | |
| is_present | | | Y | Y | Y | Y | Y | Y |

*Answer (2026-10-22):* The proposed matrix is correct.

**Q10. What do `has` and `is_present` mean?**
*Proposed:*
- `has`: a list attribute (events or attachments) contains an item matching the test, as a case-insensitive substring. For example, attachments `has` `.pdf`.
- `is_present`: the attribute is non-empty. It takes no test value.

*Answer (2026-10-22):* `has` means **has any**: the list (events or attachments) has at least one item, and it takes no test value. `is_present` means the attribute is non-empty, with no test value. For events and attachments the two behave the same.

**Q11. What is the difference between `check_for_events` and `create_events`?**
`check_for_events` isn't defined in the requirements.
*Proposed:*
- `check_for_events`: the LLM identifies events and stores them against the email, and suggests a calendar for each. Nothing is added to a calendar.
- `create_events`: adds the stored events to a calendar, identifying them first if there are none.

*Answer (2026-10-22):* Identify vs add, as proposed.

**Q12. How do classification rule actions and action rules work?**
*Proposed:*
- Classification: actions that only change the email record (`set_type`, `set_reply_needed`, `skip_processing`, `fetch_link_contents`, `check_for_events`) run immediately. Actions with side effects are queued and run in the action phase.
- Action phase: **every** enabled action rule that matches is applied, in priority order. This also completes the cut-off sentence in N2.
- Execution order is fixed, whichever rule an action came from:
  1. record changes
  2. fetch, LLM, downloads, events and bills
  3. forward
  4. mark read or unread
  5. move or delete, last
- If rules conflict on move or delete, the highest-priority rule wins and the others are logged as errors.

*Answer (2026-10-22):* As proposed.

**Q21. How do several conditions on one rule combine?** *(Raised by the tester agent, 2026-10-22.)*
A rule has "one or more" conditions, but the requirements don't say how they combine.
*Proposed:* all conditions must match (AND). For "either X or Y", create two rules.
*Answer (2026-10-22):* All must match (AND).

**Q22. Where do enums and other shared vocabulary live?** *(Raised by you, 2026-10-23.)*
The builder had created a `domain` package, which the requirements don't define. It held the enums stored as codes (`RuleType`, `ConditionAttribute`, the match types, the email statuses), the `ActionTypes` names and the JPA enum converter.
*Answer (2026-10-23):*
- The enums and `ActionTypes` move to `models`.
- The JPA converter moves to `entities`.
- There is no `domain` package.
- The other supporting packages stay and will be documented in the architecture doc: `config` (startup config and checks), `mappers` (MapStruct) and `util` (duration, name and time helpers).
- Any further package outside the requirements' structure must be proposed to you first.

### Emails and processing

**Q13. What does "action required" mean?**
It isn't defined in the requirements.
*Proposed:* an `action_required` flag that is set:
- by a new `set_action_needed` action
- automatically for bills
- automatically for events not yet added to a calendar

You can clear it in the UI and through MCP.
*Answer (2026-10-22):* ~~A flag set by an action and automatically, as proposed.~~ **Superseded by the revised answer below.**

*Revised answer (2026-10-22, your correction):* `action_required` means **the text of the email implies you need to do something**. Emails of type `Bill` never have it set, because bills are handled by `process_bill`.
- The LLM decides it. The Summarise prompt now returns `action_required` and a short `action` description (see R1), which is stored in a new `action_description` column.
- There is **no** `set_action_needed` action, and the flag is **not** set automatically for bills or for events.
- The code enforces the Bill exclusion: when an email's type is `Bill`, `action_required` is false. This also applies if the type is changed to `Bill` later, in the UI or through MCP.
- You can still clear the flag in the UI and through MCP once you've done what was needed.

**Q14. How should emails be fetched?**
"Stop at the first already-processed email" permanently skips older emails if a run fails partway through.
*Proposed:* process every INBOX email not yet in the database (matched by Message-ID), newest first, within a lookback limit. The limit is a setting (default: 7 days) so the first run doesn't send the whole inbox to the LLM.
*Answer (2026-10-22):* All unprocessed email within a lookback window, as proposed.

**Q15. How should the code find its prompts, and what is the LLM call flow?**
Prompt names are editable, so the code can't rely on them.
*Proposed:*
- Each system prompt gets a fixed `key` that the code uses. Names and text stay editable, but system prompts can't be deleted.
- Unclassified emails get the Classify prompt (which returns the type only), followed by the Summarise prompt.
- Classified emails get only the Summarise prompt.
- I'll write the `identify_events` prompt that `create_events` refers to.
- "Selecting a calendar" is used when an events action has no calendar set.

*Answer (2026-10-22):* Fixed keys and a two-step flow, as proposed.

**Q16. How is a bill recognised, and where does the payment reminder go?**
*Proposed:*
- The `Bill` type is a system type with a fixed key, so renaming it is safe and it can't be deleted.
- The reminder goes to a calendar named in a setting (`bill_reminder_calendar`), as an all-day event on the day before the due date.

*Answer (2026-10-22):* `Bill` is a system type with a fixed key and can't be deleted. Each `process_bill` action takes a **destination calendar** for the reminder, so there is no `bill_reminder_calendar` setting. The reminder is an all-day event on the day before the due date.

### UI

**Q17. How should encrypted settings be displayed?**
*Proposed:* masked (`••••••••`) rather than as `enc:…` ciphertext. Clicking gives an empty field for entering a new value.
*Answer (2026-10-22):* Masked.

**Q18. Which palettes?**
Alucard is the official light theme of **Dracula**. Darcula is JetBrains' dark theme.
*Proposed:* Dracula (dark) and Alucard (light).
*Answer (2026-10-22):* Dracula and Alucard.

**Q19. What does markdown support in the prompt editor mean?**
*Proposed:* a large textarea with a Preview toggle that renders the markdown. Placeholders are inserted at the cursor from a dropdown and use the form `{{calendars}}`. Double braces avoid clashing with the JSON in the prompts.
*Answer (2026-10-22):* A textarea with a preview toggle.

**Q20. Housekeeping. Which of these should I do?**
- `git init` and commit as I go
- Remove the stray number at line 195 (N1)
- Playwright browser tests for the JavaScript UI behaviour
- A dashboard with last run, next run and a "Run now" button

*Answer (2026-10-22):* All four: `git init` and commit as I go, remove the stray number, Playwright browser tests, and a dashboard with "Run now".

---

## Proposed defaults

I'll adopt these unless you object.

- **D1. Email status values.**
  - The values are `pending` (being processed), `processed`, `skipped` (by `skip_processing`, but still recorded so it isn't reprocessed) and `failed`.
  - Errors are tracked separately and shown as an error count and a red row.
- **D2. Types.**
  - Colour is set with a picker or a hex code.
  - Description is optional, because `Legal` has none.
  - Names are unique.
  - Deletes are soft.
- **D3. Soft deletes everywhere.** Calendars and prompts get `deleted` and `deleted_at` columns too, matching rules and types.
- **D4. Calendars.**
  - "Durations" is the `notification_periods` field.
  - The name is chosen from a dropdown of the calendars in the khal config, so it always matches khal.
- **D5. Rules.**
  - Add a `description` column.
  - Names are unique per rule type and can't be edited.
  - Priority is an integer of 0 or more, default 0. Higher priorities run first, and ties go to the older rule.
  - `is`, `contains` and `starts_with` are case-insensitive. `regex` is case-sensitive unless it uses `(?i)`.
  - `message` matches the plain-text body, or text extracted from the HTML if there is no plain-text part.
- **D6. Rule_Actions.**
  - Add `notifications` (for `create_events`) and `position` (display order) columns.
  - For `create_events` and `process_bill`, the destination is a calendar chosen from a dropdown (see Q16).
  - An action's notifications override the calendar's `notification_periods`.
- **D7. Emails schema.**
  - Add `message_id` (unique), `imap_uid`, `uid_validity`, `folder`, `html_message`, `link_contents`, `link_summary`, `status`, `action_required`, `action_description`, `is_read`, `deleted` and `deleted_at`.
  - Normalise attachments, rule matches, action results and errors into the `Email_attachments`, `Email_rule_matches`, `Email_action_results` and `Email_errors` tables. The UI needs per-attachment downloads and per-rule action lists.
- **D8. Email_events.**
  - Add `notes`, `ends_at`, `all_day`, `khal_uid` and a calendar foreign key.
  - A date with no time becomes an all-day event. A datetime uses the default duration setting (1h).
  - Times with no zone use the timezone setting (default `Australia/Melbourne`).
- **D9. Settings.**
  - Add `type` (text, duration, int, bool, encrypted, path or list) and `description` columns, used for validation and help text.
  - The set of settings is fixed and seeded by migrations, so the UI edits settings but doesn't add them.
  - Extra settings:
    - IMAP: port, TLS, inbox folder and trash folder
    - SMTP (see Q7)
    - LLM: model, timeout, maximum input characters
    - timezone and default event duration
    - processing frequency (default 1h) and lookback (see Q14)
    - fetch user agent and timeout
    - anchor text patterns for pay and bill links
- **D10. Email list.**
  - The default view shows the 30 most recent emails. Filtered results are paginated in pages of 50.
  - Emails are sorted by received date, newest first.
  - Times are shown as relative values, with the absolute time in a tooltip.
- **D11. Email delete button.** Deletes through IMAP (see Q6) and soft-deletes the record, so the email is kept for history and not reprocessed.
- **D12. Responsive tables.** This completes the cut-off sentence in N2.
  - Each column has a priority, and the lowest-priority columns hide first as the screen narrows.
  - Full details are always on the detail page.
- **D13. MCP.**
  - Streamable HTTP at `/mcp`, on the internal Docker network only (not exposed through nginx).
  - Tools: search and get emails; set type; move; mark read or unread; delete; forward.
  - Extra tools: list types, folders and calendars; change an event's calendar; set or clear response required and action required; reprocess an email.
- **D14. Bill parsing.**
  - The amount regex also accepts thousands separators and amounts without cents, and prefers an amount near "due", "total" or "amount due".
  - Dates in `dd/MM/yyyy` form are also accepted.
  - Pay and bill link anchor text is configurable.
  - Links that need a login fail, and the failure is recorded as an error on the email.
- **D15. Fetching links.**
  - Pages are fetched with headless Chromium (`--dump-dom`) using a Chrome-on-Windows user agent. Files are downloaded with curl.
  - Pages that need JavaScript work, but pages behind a login (Compass, for example) don't.
- **D16. Reading mail doesn't mark it as read.** Messages are fetched with `BODY.PEEK`, so only `mark_read` changes the read flag.
- **D17. Scheduler.**
  - The interval comes from the settings, and a change takes effect without a restart.
  - Only one run happens at a time.
- **D18. Calendar events.** khal has no non-interactive command to delete or move an event. Instead:
  - Postmaster writes each event as a `.ics` file with its own UID and adds it with `khal import`.
  - Moving or deleting an event moves or deletes that file in the calendar's vdir.
  - The host's vdirsyncer cron job then syncs the change.

---

- **D19. Fixes to the seeded prompts.** These are needed for the prompts to work as intended. You can still edit them all in the UI.
  - "Summarising the email" has no placeholder for the email itself, so I'll add `Sender: {{sender}}`, `Subject: {{subject}}` and `Contents: {{text}}`.
  - The JSON example in "Summarising the email" is invalid: commas are missing after `title` and `location`. Small models tend to copy mistakes like this, so I'll fix it.
  - The Classify prompt's type table uses a `{{types}}` placeholder, which isn't in the requirements' placeholder list. I'll add it.
  - "Selecting a calendar" gets `{{calendars}}` (enabled calendars only) and `{{events}}` (with event ids).
  - Typos are corrected, for example "there many be" → "there may be".

- **D20. Name uniqueness ignores case (added 2026-10-22).** Type names, calendar names and rule names (within a rule type) are unique regardless of case, so `Bill` and `bill` can't both exist.
  - This matches MariaDB's default case-insensitive collation.
  - It is enforced in the service layer, so tests on H2 behave the same as production.
  - Schema details kept portable between H2 and MariaDB:
    - no column name is a reserved word in either database. With your permission (2026-10-22), reserved-word columns are renamed rather than quoted: `Settings.key` → `setting_key`, `Settings.value` → `setting_value`, `Email_events.timestamp` → `starts_at` (pairs with `ends_at`)
    - table names match exactly
    - timestamps are UTC `DATETIME`s
    - unique columns stay within MariaDB's index size limit

---

## Revisions

**R1 (2026-10-22). The Summarise prompt also identifies required action (from the Q13 revision).**
The seeded "Summarising the email" prompt becomes:

    Analyse the text of this email to identify if there are any events (there may be zero, one or multiple) that can be added to a calendar, and if there is a link to a page then extract the link. Summarise the contents of the email message. Attempt to identify if a response is required. Also attempt to identify if the email asks or implies that the recipient needs to do something other than reply, such as complete or sign a form, make a booking or payment, provide information, or do something by a deadline. Return the details as JSON with this structure:

    {
      "summary": "<summary of email>",
      "link": "<url of link>",
      "response_required": <true|false>,
      "action_required": <true|false>,
      "action": "<short description of what needs to be done, including any deadline>",
      "events": [
        {
          "title": "<title of your event>",
          "when": "<string - ISO 8601 datetime without zone>",
          "location": "<where the event is, if it can be determined, leave null otherwise>",
          "notes": "<string> any other details worth noting"
        }
      ],
      "errors": [
        <list of strings specifying errors>
      ]
    }

    Do not guess any details. Only the summary is mandatory; include the other fields only if values are detected. Report errors when processing email, if any.

    Sender: {{sender}}
    Subject: {{subject}}
    Contents: {{text}}

`action_required` from the LLM is ignored when the email's type is `Bill`.

---

## Technical suggestions

- **S1. Docker, not Podman.** MariaDB, Dovecot, nginx and Hermes are all on Docker networks (`mariadb` and `services`), so Postmaster must be too. It will use a compose file plus a systemd unit, like Hermes.
- **S2. Flyway** for SQL migrations, run at startup.
- **S3. Structure.**
  - Controllers, Services and Repositories as specified, with Hibernate ORM Panache (Kotlin) repositories.
  - An `actions` package holds an `ActionHandler` interface and a registry.
  - Shared helpers: `HtmlParser`, `HttpFetcher`, `LlmClient`, `MailboxClient`, `CalendarClient` and `Crypto`.
- **S4. UI.**
  - Qute server-side templates plus HTMX.
  - HTMX and the font files are bundled with the app (no CDN).
  - The app is served under the `/mail` root path.
- **S5. MapStruct needs kapt with Kotlin.** This works, but kapt is in maintenance mode.
- **S6. Tests.**
  - H2 in MariaDB mode.
  - GreenMail for IMAP and SMTP, WireMock for the LLM and HTTP fetches, and a fake `khal` script.
  - RestAssured with Jsoup assertions on the rendered HTML.
- **S7. Encryption.** AES-256-GCM with a random IV, stored as `enc:` followed by base64. The key comes from the YAML config file.
- **S8. OpenAPI.** Generated by smallrye-openapi from annotations and written to `docs/openapi.yml` at build time.
- **S9. Container base image.**
  - Debian slim, because Ubuntu ships Chromium only as a snap.
  - Includes khal, curl and Chromium.
  - Runs as the `postmaster` user, in the `calendars` group.
- **S10. LLM calls.**
  - Use the OpenAI-compatible chat completions API.
  - Use JSON or schema-constrained output for the prompts that return JSON.
  - Turn off "thinking" for the Qwen models.
  - Truncate input to the maximum characters setting.
- **S11. Startup.** The YAML config path comes from the single command line argument. A small custom `main` sets the Quarkus config location before startup.
