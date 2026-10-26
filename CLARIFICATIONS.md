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
*Answer (2026-10-22):* All must match (AND). *(Revised in R8: each rule can be set to AND or OR.)*

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

**R2 (2026-10-24). A local dev mode with fake data (your new requirement).**
You want to run Postmaster locally through Gradle, in dev mode or a dev profile, on an in-memory database filled with fake data, so you can see what the UI looks like.
- `./gradlew quarkusDev` (or `bin/dev`) runs the `dev` profile on in-memory H2 in MariaDB mode. No MariaDB, Dovecot, LLM or configuration file of your own is needed.
- The H2 driver is added to the build only when `quarkusDev` runs, so production builds and the native image don't include it.
- `config.DevData`, a bean built only into the dev profile, fills the database at startup. It creates:
  - calendars and rules
  - about 60 emails in every state, with events, attachments, rule matches, action results and errors
  - processing runs

  It also writes a khal config and the attachment files under `build/dev/`.
- Scheduled processing is off in dev mode. "Run now" and mailbox actions fail cleanly, because there is no mail server.
- Dev mode's startup configuration is `config/dev.yml`, which the Gradle `quarkusDev` task passes as the application's argument. Production still requires the argument (S11).

**R3 (2026-10-24). Logging (your new requirement).**
- Logs go to stdout in every profile, including production, so Docker collects them. There is no log file. The format is plain text, not structured.
- The MDC carries the context, shown with `%X` in each line, and is removed when that work ends:

  | Key | Value |
  |---|---|
  | `run` | the processing run's id |
  | `message_id` | the message's Message-ID, while it's fetched |
  | `email` | the email's id |
  | `phase` | `classification`, `llm` or `action` |
  | `rule` | the rule's name |
  | `action` | the action type |
  | `source` | `ui` or `mcp`, for manual actions |
- `util.Mdc.with(...)` sets keys for a block of code and restores the previous values afterwards, so nested work (run, email, phase, rule and action) cleans up after itself.

**R4 (2026-10-24). The Water.css palettes replace Dracula and Alucard (supersedes Q18).**
After comparing the two, you chose Water.css for light and dark mode.
- **Colours:** the CSS tokens take Water.css's values. Its link blue is the accent (`--pm-accent`, formerly `--pm-purple`): primary buttons, select arrows and counts.
- **Status colours:** Water.css has none, so red, orange, yellow, green and pink are chosen to suit it.
- **Favicon:** the envelope on the accent blue, light or dark to match the device.
- **Unchanged:** the seeded type colours (from Dracula) stay, because they're data you can change on the Types page.

**R5 (2026-10-25). Action items and the `update_todo_list` action (your new requirement).**
An email can ask for several things to be done, and a new action writes them to a markdown to-do list.
- **Action items.** You chose a list over the single action an email had before.
  - The `summarise` prompt asks for `action_items`, each with an `action`, an optional `due` date (`yyyy-mm-dd`) and `notes`. It also gets the new `{{received}}` placeholder (e.g. "Sunday 25 October 2026, 14:03", in `processing.timezone`), so deadlines like "in three days" can be worked out.
  - Migration V5 changes the seeded prompt only where its text still matches. A prompt that answers with the old `action_required` and `action` still works: the action becomes one item.
  - Items are stored in `Email_action_items`. `Emails.action_required` and `action_description` stay, kept in step with the items that aren't done, so filters, the dashboard and MCP work as before.
  - Bills never have action items (Q13). Reprocessing drops the items that aren't in the to-do list and doesn't add an item twice.
- **The to-do list.** Two new settings, in a "To-do list" group: `todo.path`, the folder (default `/data/todo`), and `todo.postmaster_url`, the address of these pages for links back to the email (empty for no links).
- **`update_todo_list`** writes each open item that isn't in the list yet:
  - **The item's file** is named after it, e.g. `Post turntable in three days.md`. Characters that can't be in a file name or a `[[link]]` are replaced, and the name is numbered if it's taken.
  - **Its front matter** is `todo-item: true`, `tags: [todo-item]`, `created` (local time when it was written, `yyyy-MM-ddTHH:mm:ss`) and `completed: false`, with `due: yyyy-mm-dd` as the second-last line when there's a due date.
  - **Below the front matter** come the item's notes (or the email's summary), the email's subject and sender, and a link to the email here.
  - **`index.md`** gets `- [ ] [[name]] #todo-item` just above its `<!-- todo-items:end -->` line. A missing `index.md` is created. One without that line fails the action, and no file is written.
- **Done** next to an item on the email page (or MCP `complete_action_item`) marks it done here, ticks it in `index.md` (`- [x]`) and sets `completed: true` in its file. If the files can't be written, nothing changes and an error says why. Clearing action required (`set_action_required` false) does this for every open item.
- **Each processing run** first checks the to-do list. Items ticked in `index.md`, or with `completed: true` in their file, are marked done here.

**R6 (2026-10-25). Processing survives interruptions, so no email is lost track of (your request).**
Before this, each email was processed in one transaction while its side effects happened outside it. A failure before the commit forgot the email: one already moved out of INBOX was never seen again, and one still there was processed again, repeating its forwards and calendar events.
- **Saved steps.** A new email is saved (`pending`) before anything is done with it, then after each step: classification, the LLM, matching the action rules, and each action. `Emails.progress` is the last step saved and `Emails.attempts` the passes started (migration V6).
- **Carrying on.** Each run starts by carrying on with every `pending` email from the step after its `progress`. The recorded action results say which actions have run.
- **Safe to repeat:**
  - a move or delete notices the message is already in its destination;
  - calendar events (and bill reminders) have UIDs made from the email and event, and an event whose UID is already in the calendar isn't added again;
  - a to-do item's name is saved before its files are written, and writing them again finishes the job.
- **Forwarding** can't be made safe to repeat. It's recorded as `started` before it sends, and a forward found `started` by a later pass is recorded as failed, telling you to check before running it again. A second copy is never sent automatically.
- **When the LLM is down** (it can't be reached or is busy, after retries after 2 and 10 seconds), the email waits for the next run before any action runs on it. After five attempts it's processed without the LLM, with the failure recorded as an error.
- **Giving up:** an email is marked failed, with an error saying why, if a sixth pass would start. Reprocess it once the problem is fixed.
- **The lookback** counts back from the start of the latest successful run, so email that arrived while Postmaster was down or failing is still found.
- **IMAP:** a failed connection is tried once more after 5 seconds.
- **Stopping:** when the application is told to stop it starts no new step and gives the current one up to 20 seconds. Run the container with `--stop-timeout 30` (or `stop_grace_period: 30s`).
- **Holding transactions:** no database transaction is open while the LLM classifies or summarises. Actions that call the LLM, khal or Chromium still run inside their own action's transaction.

**R7 (2026-10-25). A status endpoint, and build numbers for versions (your request).**
- **Endpoint:** `GET /mail/health`, also at `/mail/status`, returns JSON. The layout follows the IETF draft "Health Check Response Format for HTTP APIs":
  - an overall `status` and the `time` of the check;
  - `application`: the name, `version`, `builtAt`, `startedAt`, `uptime` (ISO 8601) and `uptimeSeconds`;
  - `processing`: whether a run is in progress, the current and last runs, when the last successful run started, whether scheduling is on and when the next run is due, how many emails are pending, and how many failed in the last day;
  - `checks`, keyed by dependency (`database`, `dovecot`, `llm`, `smtp`, and the others below): each with `status` (`UP`, `WARN` or `DOWN`), `critical`, `componentType`, `target` (no credentials), `responseTimeMs` and `output` (why it's down, or the LLM's models).

  Every field is always present, null when there's no value. The response isn't cached (`Cache-Control: no-store`).
- **Checks:**
  - the database: a connection is validated;
  - Dovecot: a login, then logout;
  - SMTP: a connection, logging in if `smtp.username` is set;
  - the LLM: `GET <llm.url>/models`.

  They run in parallel, each given 5 seconds, without the usual retries, so the endpoint answers within about 6 seconds.
- **Overall status:**
  - `OK`: everything is up.
  - `DEGRADED`: the LLM or SMTP is down, or another check below is down or warns. Postmaster carries on: email waits for the LLM (R6), and forwarding fails.
  - `DOWN`: the database or Dovecot is down. Postmaster is running but can't do its job. The response is HTTP 503 (200 otherwise), so `curl -f` and Docker's `HEALTHCHECK` (now in the image) treat it as unhealthy. `DOWN` is the usual word for this (MicroProfile Health, Spring Boot), and the response arriving at all shows the process is up.
- **Versions:** the version is a build number, shown as `v<n>`, starting at v1. `version.properties` holds the number the next build gets. Every successful `quarkusBuild` raises it by one (`-PkeepVersion` builds without raising it), and so does `bin/build-image`, which also tags the image `postmaster:v<n>`. Commit the file with each release. `builtAt` is set only by production builds; dev mode and tests show null.
- **Dev mode** points the LLM at 127.0.0.1, so it never contacts kodiakbear.
- **More checks (your request, suggestions 3 to 7).** None of them is critical, so a problem makes the status `DEGRADED`:
  - `settings`: every encrypted setting can be decrypted with `postmaster.encryption-key`;
  - `processing`: warns (`WARN`) when no scheduled run has succeeded for two processing periods (counted from startup if that's later), or when more emails are pending than `health.pending_limit` (25);
  - `khal`: `khal printcalendars` runs, and every calendar folder in the khal configuration can be written to;
  - `chromium` and `curl`: `--version` runs (their output is the version);
  - `attachments` and `todo`: the folder can be written to, or doesn't exist yet but can be created; the to-do `index.md`, if there is one, has its `<!-- todo-items:end -->` line;
  - `disk`: warns when a disk holding attachments, the to-do list or a calendar has less free space than `health.min_free_mb` (1024 MiB).

  A check's `status` is now `UP`, `WARN` (usable, but needs attention) or `DOWN`. The two thresholds are settings, under "Status checks" on the Configuration page.
- **Commit:** `application.commit` is the git commit the build is from, with `-dirty` if it had uncommitted changes. The Docker build has no `.git`, so `bin/build-image` passes it in (`-PgitCommit`).
- **The IETF draft's own format:** a client sending `Accept: application/health+json` gets the draft's format, as `application/health+json`: `status` is `pass`, `warn` or `fail`; `version`, `releaseId` (the commit), `serviceId`, `description`, and `output` saying what's wrong; `checks` has `uptime` and `<check>:responseTime` for each check, each a list with `componentId` (the target), `componentType`, `observedValue`, `observedUnit`, `status`, `time` and `output`. Fields without a value are left out, as the draft asks. Without that `Accept` header the response stays as above, with `OK`, `DEGRADED` and `DOWN`. HTTP codes are the same in both.

**R8 (2026-10-25). Rules can match any of their conditions (your request; revises Q21).**
Each classification and action rule says how its conditions combine, for the whole rule:
- **All** (AND, the default): every condition must match, as before. Existing rules keep it.
- **Any** (OR): one matching condition is enough.
- It's the "Match" field above the conditions on the rule form, and the rules list shows "(all)" or "(any)" next to the condition count for rules with more than one. It's stored in `Rules.condition_join` (`all` or `any`, migration V8).
- A rule with no conditions still never matches.

**R9 (2026-10-26). Folders, tracking, done emails, events or action items, to-do status, absolute reminders (your requests).**
- **Folders.** The mailbox's folders are kept in a new `Folders` table. They're scanned every `imap.folder_scan_frequency` (12h) and once at startup, and the email's Move dialog has a "Refresh" button that scans them again. The rule form's `move` "to:" field and the Move dialog both choose from this list. A rule whose folder is no longer listed keeps it, shown as "(not found)". MCP `list_folders` answers from the list too.
- **Tracking.** Each email has a `tracking` state (migration V9):
  - `tracked`, the default;
  - `moved`, when it's no longer in the folder Postmaster expects it in;
  - `deleted`, when it's in the trash folder, or was deleted by Postmaster (the Delete button, MCP or a `delete` or `move`-to-trash action);
  - `done`, when you mark it done (below).

  Emails in any state but `tracked` are **untracked**.
  - Every `tracking.frequency` (1h), tracked emails received within `tracking.window` (7d) are looked for in their folder by Message-ID. One that isn't there is looked for in the trash folder: there it's `deleted`, otherwise `moved`. Other folders aren't searched. If Dovecot can't be reached, nothing changes. Emails still being processed (`pending`) are left alone.
  - Untracked emails can still be opened, but they're greyed out and nothing can be done to them: every action (by hand or MCP) is refused, except reopening a done email.
  - The emails list leaves them out unless "Include deleted, moved and done" is ticked (MCP `search_emails`: `include_untracked`). They show greyed out with their state. The dashboard's counts leave them out.
  - Deleting no longer soft-deletes an email; it becomes `deleted` and can still be opened. Emails soft-deleted before V9 become `deleted` too.
- **Done.** "Mark done" on the email page (MCP `mark_email_done`) completes every open action item, ticking them off in the to-do list, clears Reply needed and makes the email `done`. "Reopen" makes it `tracked` again; its items stay done.
- **Events or action items.** Something is a calendar event or an action item, never both. Anything with a date (and perhaps a time) is an event; something to do with no date is an action item.
  - The summarise prompt says so, and no longer asks for a due date on action items (migration V9 changes the seeded prompt only where its text still matches).
  - An action item the LLM still gives a date becomes an all-day event on that date, unless the email already has an event on that day with the same title (or one containing the other).
  - **Event to action item:** an event that hasn't been added to a calendar can be made an action item from its dialog. The item's due date is the event's day. It's written to the to-do list if `update_todo_list` has run on the email.
  - **Action item to event:** an open action item can be made an event, choosing its date (the due date at first), an optional time, and optionally a calendar to add it to straight away. An item in the to-do list is removed from it: its file and its line in `index.md`.
- **To-do status.** Items can be open, in progress or done. In the to-do list they're marked both ways, kept in step:
  - in `index.md`: `- [ ]` open, `- [/]` in progress, `- [x]` done;
  - in the item's front matter: `status: todo`, `in-progress` or `done`, with `completed: true` only when done. New items get `status: todo`.

  Every `todo.sync_frequency` (1h), instead of at the start of each processing run, Postmaster reads both for each item it wrote. Where one has changed since Postmaster last saw it, the item takes that status here (also back to open), and the other is updated to match. If both have changed, the index wins. In-progress items show "In progress" on the email page; they still need action.
- **Status check.** A new `todo_list` check reads `index.md` in `todo.path`: it's `DOWN` if it can't be read or written or has no `<!-- todo-items:end -->` line, and warns if it doesn't exist or if files of items Postmaster wrote are missing. Otherwise it reports how many items are open, in progress and done. The `todo` check keeps testing the folder.
- **Absolute reminders.** Notifications (a `create_events` action's and a calendar's Durations) also take `d-1@20:00`: a reminder at a time of day, a number of days before the event's day (`d@08:00` is on the day). Times are 24-hour ISO 8601 (`20:00`) or 12-hour (`8pm`, `8:30am`), in `processing.timezone`. They mix with durations, e.g. `1h, d-1@20:00`. They're written as absolute iCalendar alarms (`TRIGGER;VALUE=DATE-TIME`).
- **Case.** `regex` conditions ignore case too, like every other string condition (revises D5). Start a pattern with `(?-i)` to match case.

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
