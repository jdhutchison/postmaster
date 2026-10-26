# MCP server

Postmaster is an MCP server for Hermes and its `postmaster` profile (REQUIREMENTS.md "MCP server"). Its tools search processed emails and act on them; the actions are the same as the UI's and are recorded against the email with source `mcp`.

- **Endpoint:** Streamable HTTP at `/mail/mcp`, e.g. `http://postmaster:8080/mail/mcp` from another container on the `services` Docker network. (The SSE transport is at `/mail/mcp/sse`.)
- **Exposure:** the endpoint has no authentication of its own, so it should only be reachable on the internal network, not through nginx (CLARIFICATIONS.md D13).

A Hermes profile entry looks like:

```yaml
mcp_servers:
  postmaster:
    url: http://postmaster:8080/mail/mcp
```

## Tools

| Tool | Arguments | Returns |
|---|---|---|
| `search_emails` | all optional: `from`, `to` (`yyyy-MM-dd`, inclusive), `status` (`pending`, `processed`, `skipped`, `failed`), `type` (type name, or `none`), `sender` (part of the name or address), `response_required`, `action_required`, `with_errors`, `include_untracked` (booleans), `page` | `{total, page, pageCount, emails}`. With no filters, the 30 most recently received emails; otherwise pages of 50, newest first. Emails that are deleted, moved or done are left out unless `include_untracked` is true. Each email has `id`, `receivedAt`, `subject`, `sender`, `senderName`, `status`, `type`, `responseRequired`, `actionRequired`, `tracking` and counts of `events`, `attachments` and `errors`. |
| `get_email` | `id` | Everything about the email: details, summary, message text, linked page, bill details, `tracking`, action items (with ids, due dates, whether each is done or in progress, and whether it's in the to-do list), events (with ids), attachments, matching rules and their actions, manual actions and errors. |
| `set_email_type` | `id`, `type` (empty clears it) | A message. Setting Bill drops the action items. |
| `move_email` | `id`, `folder` | A message |
| `mark_email_read` | `id`, `read` (default true) | A message |
| `delete_email` | `id` | A message. The message moves to the trash folder, and the email is no longer tracked: it appears in searches only with `include_untracked`. |
| `forward_email` | `id`, `to` | A message |
| `set_response_required` | `id`, `required` | A message |
| `set_action_required` | `id`, `required`, `description` (optional) | A message. Setting it adds an action item; clearing it marks every open item done, in the to-do list too. Refused for bills. |
| `complete_action_item` | `email_id`, `item_id` | A message. The item is marked done and ticked off in the to-do list if it's there. |
| `mark_email_done` | `id` | A message. The email's open action items are marked done (and ticked off in the to-do list), it no longer needs a reply, and it's no longer tracked. |
| `reopen_email` | `id` | A message. An email marked done is tracked again; its action items stay done. |
| `set_event_calendar` | `email_id`, `event_id`, `calendar` | A message. An event in a calendar is moved; one that isn't is added. |
| `reprocess_email` | `id` | A message. Rules, the LLM and actions run again. |
| `list_types` | | `{types: [{id, name, description}]}` |
| `list_folders` | | `{folders: [...]}`, as last scanned (`imap.folder_scan_frequency`) |
| `list_calendars` | | `{calendars: [{id, name, colour}]}` (enabled calendars) |

Names of types and calendars are matched ignoring case. Times are ISO 8601 in UTC (`2026-10-21T00:00:00Z`); event times are local times in the `processing.timezone` setting.

Only tracked emails can be acted on (CLARIFICATIONS.md R9). `tracking` is `tracked`, `moved` (no longer in its folder), `deleted` (in the trash folder, or deleted by Postmaster) or `done`; an action on an email that isn't tracked is refused, except `reopen_email` on one that's done.

Bad arguments, unknown ids and failed actions come back as tool errors (`isError: true`) whose text says what went wrong, e.g. `There is no folder named 'Folders/Bils'`.
