# Actions

Rules trigger actions. Each action type is an `ActionHandler` bean in the `actions` package; `ActionRegistry` collects every handler, so adding an action type needs no other wiring.

## Built-in actions

| Action | Stage (order) | What it does |
|---|---|---|
| `set_type` | record | Sets the email's type. Setting Bill clears action required. |
| `set_reply_needed` | record | Sets response required. |
| `skip_processing` | record | Stops processing; queued actions are recorded as skipped and the email's status is `skipped`. |
| `fetch_link_contents` | gather (10) | Fetches the email's link with headless Chromium, stores its text and the LLM's summary of it. |
| `check_for_events` | gather (20) | Asks the LLM for events if the email has none, and suggests a calendar for each. Nothing is added to a calendar. |
| `download_attachments` | gather (30) | Saves the attachments of interest under `attachments.path`, for download from the UI. |
| `create_events` | gather (40) | Adds the email's events to the action's calendar (or the suggested one, or one the LLM picks), with the action's notifications or the calendar's. |
| `process_bill` | gather (50) | For Bill emails: finds the biller, amount, due date and links, adds a reminder the day before it's due, and saves the bill. |
| `forward_email` | forward | Forwards the email with its attachments to the action's address. |
| `mark_read`, `mark_unread` | read state | Sets or clears `\Seen` in the mailbox. |
| `move` | move (0) | Moves the message to the action's folder. |
| `delete` | move (1) | Moves the message to `imap.trash_folder`. |

Stages run in the order shown. Within a stage, handlers run by `order`, then by rule priority. In the read-state and move stages, only the highest-priority rule's action runs; the others are recorded as conflicts (CLARIFICATIONS.md Q12).

## Adding an action type

1. **Write a handler.** Create an `@ApplicationScoped` class in `sh.hutch.postmaster.actions` that implements `ActionHandler`:

   ```kotlin
   @ApplicationScoped
   class StarHandler(private val mailbox: MailboxClient) : ActionHandler {
     override val actionType = "star"
     override val stage = ActionStage.READ_STATE
     override val order = 10

     override fun execute(context: ProcessingContext, action: RuleActionEntity): ActionOutcome {
       val (folder, uid) = context.email.location(mailbox)
       // … use the shared helpers …
       return ActionOutcome.done("Starred")
     }
   }
   ```

   - `actionType` is the name stored in `Rule_actions.action_type`. Action types are plain strings, so no migration is needed.
   - `stage` decides when the action runs; `order` orders handlers within a stage.
   - Return `ActionOutcome.done(detail)`, `skipped(detail)` or `failed(detail)`. Throwing records the action as failed with the exception's message, and adds an error to the email.
   - `context.email` is the `EmailEntity` being processed and `context.message` the parsed message. Changes to the entity are saved with the email.
   - `action.destination`, `action.type`, `action.calendar` and `action.notifications` are the rule action's values.

2. **Use the shared helpers** rather than talking to the outside world directly:

   | Helper | For |
   |---|---|
   | `MailboxClient` | Reading, moving, flagging, deleting and forwarding messages, and saving attachments. `EmailEntity.location(mailbox)` finds the message's folder and UID. |
   | `LlmClient` + `PromptRenderer` | Calling the LLM with a stored prompt (`chat`, `chatJson`). |
   | `HttpFetcher` | Fetching a page with headless Chromium or downloading a file with curl. |
   | `HtmlParser` | Extracting text and finding links in HTML. |
   | `CalendarClient` | Adding, moving and deleting khal events. |
   | `EventFinder` | Finding events in an email with the LLM and suggesting calendars. |
   | `AttachmentStore` | Where downloaded files go. |
   | `SettingsService` | Reading settings. |

3. **Rules can use it straight away.** `RuleService` accepts any registered action type, and the rule editor lists every type in `ActionRegistry.options()`, named by the handler's `label` (the built-in labels are in `RuleOptions.ACTION_LABELS`). If the action needs a destination, type or calendar, add it to the field rules in `RuleService` and to the field's `data-pm-for` list in `templates/tags/actionRow.html`, which decides which fields the editor shows (see `move` and `create_events`).

4. **Test it** like the built-in handlers, in `src/test/kotlin/sh/hutch/postmaster/processing/ActionHandlersTest.kt`, with GreenMail, WireMock and the fake khal.
