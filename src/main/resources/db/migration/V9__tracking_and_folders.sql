-- Folders, tracking, done emails, to-do status and events or action items (CLARIFICATIONS.md R9).

-- The mailbox's folders, scanned every imap.folder_scan_frequency, for the move dropdowns.
-- scanned_at is when the folder was last seen.
CREATE TABLE Folders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  scanned_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL
) ${table_options};

-- tracking: tracked, moved (not in the folder it's expected in), deleted (in the trash folder, or
-- deleted by Postmaster) or done (marked done). Only tracked emails can be acted on.
ALTER TABLE Emails ADD COLUMN tracking VARCHAR(10) NOT NULL DEFAULT 'tracked';
ALTER TABLE Emails ADD COLUMN tracking_changed_at DATETIME(6) NULL;
ALTER TABLE Emails ADD COLUMN tracking_checked_at DATETIME(6) NULL;

CREATE INDEX idx_emails_tracking ON Emails (tracking, received_at);

-- Deleting an email no longer hides it: it's untracked and can still be opened
UPDATE Emails SET tracking = 'deleted', tracking_changed_at = deleted_at, deleted = FALSE, deleted_at = NULL
WHERE deleted = TRUE;

-- An action item can be in progress in the to-do list
ALTER TABLE Email_action_items ADD COLUMN in_progress BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO Settings (setting_key, label, setting_value, value_type, allowed_values, description, position, created_at, last_updated_at) VALUES
('imap.folder_scan_frequency', 'Folder scan frequency', '12h', 'duration', NULL,
  'How often the list of mailbox folders, used to choose where to move email, is read again.', 170, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('todo.sync_frequency', 'To-do list check frequency', '1h', 'duration', NULL,
  'How often the to-do list is read for items that are done or in progress.', 820, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('tracking.frequency', 'Tracking check frequency', '1h', 'duration', NULL,
  'How often tracked emails are looked for in their folder, to notice those moved or deleted.', 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('tracking.window', 'Tracking window', '7d', 'duration', NULL,
  'Only emails received within this period are looked for.', 1010, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Something is an event or an action item, never both: anything with a date is an event. A prompt
-- that has been edited keeps its text where it no longer matches.
UPDATE Prompts SET contents = REPLACE(contents,
'List each separate thing to be done as its own action item. Return the details',
'List each separate thing to be done as its own action item. Something is either an event or an action item, never both: anything that happens or has to be done on or by a particular date (and perhaps a time), deadlines included, is an event, with that date as its time. An action item is something to be done that has no particular date. Return the details')
WHERE prompt_key = 'summarise';

UPDATE Prompts SET contents = REPLACE(contents,
'      "action": "<short description of one thing that needs to be done, phrased as a task>",
      "due": "<the deadline as a date, yyyy-mm-dd, if one is given or can be worked out from when the email was received, leave null otherwise>",
',
'      "action": "<short description of one thing that needs to be done, phrased as a task>",
')
WHERE prompt_key = 'summarise';

UPDATE Prompts SET contents = REPLACE(contents,
'      "when": "<string - ISO 8601 datetime without zone>",',
'      "when": "<string - ISO 8601 date, or datetime without zone, worked out from when the email was received if need be>",')
WHERE prompt_key = 'summarise';
