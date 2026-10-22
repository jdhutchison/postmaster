-- Known data set for integration tests (PLAN 1.5).
--
-- Loaded after the production migrations only by KnownDataProfile
-- (quarkus.flyway.locations=db/migration,db/testdata). The seeded types,
-- prompts and settings are kept; everything here is added. Rows have
-- distinctive names so tests find them by name, never by id.
--
-- Calendar names match the test khal config (FakeTools.CALENDARS). Dinners is
-- in that khal config but deliberately not configured here.
--
-- Timestamps are fixed. *_at columns are UTC; Email_events times are local
-- wall-clock times in processing.timezone (docs/dev/contracts.md).

-- Types: the nine seeded types, plus one soft-deleted type

INSERT INTO Types (system_key, name, description, colour, deleted, created_at, last_updated_at, deleted_at) VALUES
(NULL, 'Retired type', 'A type that was deleted', '#44475a', TRUE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-02-01 00:00:00', TIMESTAMP '2026-02-01 00:00:00');

-- Calendars: three enabled, one disabled, one soft-deleted

INSERT INTO Calendars (name, description, colour, enabled, notification_periods, deleted, created_at, last_updated_at, deleted_at) VALUES
('Family-and-Kids', 'School events, excursions and family activities', '#50fa7b', TRUE, '1d, 2h', FALSE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL),
('Kids medical', 'Doctor, dentist and other medical appointments for the kids', '#ff5555', TRUE, '1d', FALSE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL),
('Football', 'Football training and matches', '#8be9fd', FALSE, NULL, FALSE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL),
('Bills', 'Reminders to pay bills', '#ffb86c', TRUE, '1d, 4h', FALSE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL),
('Retired calendar', 'A calendar that was deleted', '#6272a4', TRUE, NULL, TRUE,
  TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-02-01 00:00:00', TIMESTAMP '2026-02-01 00:00:00');

-- name_key is the lower-cased name of an active calendar and NULL for a deleted one (CLAR D20)

UPDATE Calendars SET name_key = LOWER(name) WHERE deleted = FALSE;

-- Prompts: the six seeded system prompts, plus a user prompt and a deleted one

INSERT INTO Prompts (prompt_key, name, contents, deleted, created_at, last_updated_at, deleted_at) VALUES
(NULL, 'Translate to English', 'Translate this email into English:

{{text}}', FALSE, TIMESTAMP '2026-03-01 00:00:00', TIMESTAMP '2026-03-01 00:00:00', NULL),
(NULL, 'Retired prompt', 'A prompt that was deleted', TRUE,
  TIMESTAMP '2026-03-01 00:00:00', TIMESTAMP '2026-04-01 00:00:00', TIMESTAMP '2026-04-01 00:00:00');

-- Rules
--
-- Classification, by priority: Retired classification (30, deleted),
-- Disabled classification (20, disabled), School newsletters (10),
-- Bills by subject (5), Payslips (5, created after Bills by subject).
-- Action, by priority: Disabled action (50, disabled), Retired action (40,
-- deleted), Pay bills (10), School events (5), Forward payslips (0).

INSERT INTO Rules (name, description, rule_type, priority, enabled, deleted, created_at, last_updated_at, deleted_at) VALUES
('School newsletters', 'Newsletters and notices from Brunswick South', 'classification', 10, TRUE, FALSE,
  TIMESTAMP '2026-05-01 00:00:00', TIMESTAMP '2026-05-01 00:00:00', NULL),
('Bills by subject', 'Email with bill in the subject', 'classification', 5, TRUE, FALSE,
  TIMESTAMP '2026-05-02 00:00:00', TIMESTAMP '2026-05-02 00:00:00', NULL),
('Payslips', 'Payslips from payroll', 'classification', 5, TRUE, FALSE,
  TIMESTAMP '2026-05-03 00:00:00', TIMESTAMP '2026-05-03 00:00:00', NULL),
('Disabled classification', 'A classification rule that is switched off', 'classification', 20, FALSE, FALSE,
  TIMESTAMP '2026-05-04 00:00:00', TIMESTAMP '2026-05-04 00:00:00', NULL),
('Retired classification', 'A classification rule that was deleted', 'classification', 30, TRUE, TRUE,
  TIMESTAMP '2026-05-05 00:00:00', TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00'),
('Pay bills', 'Process bills and file them', 'action', 10, TRUE, FALSE,
  TIMESTAMP '2026-05-06 00:00:00', TIMESTAMP '2026-05-06 00:00:00', NULL),
('School events', 'Add school events to the family calendar', 'action', 5, TRUE, FALSE,
  TIMESTAMP '2026-05-07 00:00:00', TIMESTAMP '2026-05-07 00:00:00', NULL),
('Forward payslips', 'Send payslips to the accountant', 'action', 0, TRUE, FALSE,
  TIMESTAMP '2026-05-08 00:00:00', TIMESTAMP '2026-05-08 00:00:00', NULL),
('Disabled action', 'An action rule that is switched off', 'action', 50, FALSE, FALSE,
  TIMESTAMP '2026-05-09 00:00:00', TIMESTAMP '2026-05-09 00:00:00', NULL),
('Retired action', 'An action rule that was deleted', 'action', 40, TRUE, TRUE,
  TIMESTAMP '2026-05-10 00:00:00', TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-06-01 00:00:00');

-- name_key: the lower-cased name of an active rule, NULL for a deleted one (CLAR D20)

UPDATE Rules SET name_key = LOWER(name) WHERE deleted = FALSE;

INSERT INTO Conditions (rule_id, attribute, match_type, test, position, created_at, last_updated_at)
SELECT r.id, c.attribute, c.match_type, c.test, c.position, TIMESTAMP '2026-05-01 00:00:00', TIMESTAMP '2026-05-01 00:00:00'
FROM Rules r JOIN (VALUES
  ('School newsletters', 'sender', 'contains', 'brunswicksouth', 0),
  ('School newsletters', 'subject', 'regex', '(?i)newsletter|excursion|notice', 1),
  ('Bills by subject', 'subject', 'contains', 'bill', 0),
  ('Payslips', 'sender', 'is', 'payroll@employer.example', 0),
  ('Payslips', 'attachments', 'has', NULL, 1),
  ('Disabled classification', 'subject', 'contains', 'anything', 0),
  ('Retired classification', 'subject', 'contains', 'retired', 0),
  ('Pay bills', 'type', 'is', 'Bill', 0),
  ('School events', 'type', 'is', 'Primary school', 0),
  ('School events', 'events', 'is_present', NULL, 1),
  ('Forward payslips', 'type', 'is', 'Payslip', 0),
  ('Disabled action', 'subject', 'contains', 'x', 0),
  ('Retired action', 'subject', 'contains', 'y', 0)
) AS c (rule_name, attribute, match_type, test, position) ON r.name = c.rule_name;

-- Rule actions. Pay bills has a disabled action (mark_read) and a deleted one (delete).

INSERT INTO Rule_actions (rule_id, action_type, destination, type_id, calendar_id, notifications, position, enabled, deleted,
  created_at, last_updated_at, deleted_at)
SELECT r.id, a.action_type, a.destination,
  (SELECT t.id FROM Types t WHERE t.name = a.type_name),
  (SELECT k.id FROM Calendars k WHERE k.name = a.calendar_name),
  a.notifications, a.position, a.enabled, a.deleted,
  TIMESTAMP '2026-05-01 00:00:00', TIMESTAMP '2026-05-01 00:00:00',
  CASE WHEN a.deleted THEN TIMESTAMP '2026-06-01 00:00:00' END
FROM Rules r JOIN (VALUES
  ('School newsletters', 'set_type', NULL, 'Primary school', NULL, NULL, 0, TRUE, FALSE),
  ('School newsletters', 'check_for_events', NULL, NULL, NULL, NULL, 1, TRUE, FALSE),
  ('Bills by subject', 'set_type', NULL, 'Bill', NULL, NULL, 0, TRUE, FALSE),
  ('Payslips', 'set_type', NULL, 'Payslip', NULL, NULL, 0, TRUE, FALSE),
  ('Payslips', 'download_attachments', NULL, NULL, NULL, NULL, 1, TRUE, FALSE),
  ('Disabled classification', 'set_type', NULL, 'Legal', NULL, NULL, 0, TRUE, FALSE),
  ('Retired classification', 'set_type', NULL, 'Legal', NULL, NULL, 0, TRUE, FALSE),
  ('Pay bills', 'process_bill', NULL, NULL, 'Bills', NULL, 0, TRUE, FALSE),
  ('Pay bills', 'move', 'Folders/Bills', NULL, NULL, NULL, 1, TRUE, FALSE),
  ('Pay bills', 'mark_read', NULL, NULL, NULL, NULL, 2, FALSE, FALSE),
  ('Pay bills', 'delete', NULL, NULL, NULL, NULL, 3, TRUE, TRUE),
  ('School events', 'create_events', NULL, NULL, 'Family-and-Kids', '1d', 0, TRUE, FALSE),
  ('School events', 'set_reply_needed', NULL, NULL, NULL, NULL, 1, TRUE, FALSE),
  ('Forward payslips', 'forward_email', 'accountant@example.test', NULL, NULL, NULL, 0, TRUE, FALSE),
  ('Forward payslips', 'mark_read', NULL, NULL, NULL, NULL, 1, TRUE, FALSE),
  ('Disabled action', 'mark_unread', NULL, NULL, NULL, NULL, 0, TRUE, FALSE),
  ('Retired action', 'delete', NULL, NULL, NULL, NULL, 0, TRUE, FALSE)
) AS a (rule_name, action_type, destination, type_name, calendar_name, notifications, position, enabled, deleted)
  ON r.name = a.rule_name;

-- Emails
--
-- Nine individual emails (one soft-deleted) received 15-22 October 2026, and
-- 60 "Weekly update" emails received hourly from 1 September 2026, so there
-- are more than 30 (the default list) and more than 50 (a page) of them.

INSERT INTO Emails (message_id, message_hash, imap_uid, uid_validity, folder, subject, sender, sender_name, type_id, status,
  message, html_message, summary, link, link_contents, link_summary,
  bill_sender, bill_due_date, bill_amount, bill_pay_link, bill_download_url,
  response_required, action_required, action_description, moved_to, is_read,
  received_at, processed_at, deleted, created_at, last_updated_at, deleted_at) VALUES
('bill-1@electriclight.example', 'tmp-101', 101, 1, 'Folders/Bills', 'Your electricity bill is ready',
  'billing@electriclight.example', 'Electric Light Company', (SELECT id FROM Types WHERE system_key = 'bill'), 'processed',
  'Your October bill of $123.45 is due by 25 Nov 2026. Pay now at https://electriclight.example/pay/abc',
  '<html><body><p>Your October bill of <b>$123.45</b> is due by 25 Nov 2026.</p><a href="https://electriclight.example/pay/abc">Pay now</a> <a href="https://electriclight.example/bill/abc.pdf">View bill</a></body></html>',
  'Electricity bill for October: $123.45 due 25 November 2026.', NULL, NULL, NULL,
  'Electric Light Company', DATE '2026-11-25', 123.45, 'https://electriclight.example/pay/abc', 'https://electriclight.example/bill/abc.pdf',
  FALSE, FALSE, NULL, 'Folders/Bills', FALSE,
  TIMESTAMP '2026-10-20 09:15:00', TIMESTAMP '2026-10-20 10:00:00', FALSE,
  TIMESTAMP '2026-10-20 10:00:00', TIMESTAMP '2026-10-20 10:00:00', NULL),
('excursion-1@brunswicksouth.example', 'tmp-102', 102, 1, 'INBOX', 'Zoo excursion permission form',
  'office@brunswicksouth.example', 'Brunswick South PS', (SELECT id FROM Types WHERE name = 'Primary school'), 'processed',
  'Grade 3 are going to Melbourne Zoo on 5 November. Please sign and return the permission form by Friday 30 October. Parent teacher interviews are on 12 November. Read the newsletter at https://brunswicksouth.example/newsletter/42',
  '<html><body><p>Grade 3 are going to Melbourne Zoo on 5 November.</p><a href="https://brunswicksouth.example/newsletter/42">Newsletter</a></body></html>',
  'Zoo excursion on 5 November needs a signed permission form by 30 October; parent teacher interviews on 12 November.',
  'https://brunswicksouth.example/newsletter/42',
  '<html><body><nav>Home</nav><h1>Newsletter 42</h1><p>Welcome to term 4.</p></body></html>',
  'Newsletter 42 welcomes families to term 4.',
  NULL, NULL, NULL, NULL, NULL,
  TRUE, TRUE, 'Sign and return the permission form by Friday 30 October', NULL, FALSE,
  TIMESTAMP '2026-10-21 08:30:00', TIMESTAMP '2026-10-21 09:00:00', FALSE,
  TIMESTAMP '2026-10-21 09:00:00', TIMESTAMP '2026-10-21 09:00:00', NULL),
('camp-1@princeshill.example', 'tmp-103', 103, 1, 'INBOX', 'Year 9 camp',
  'admin@princeshill.example', 'Princes Hill SC', (SELECT id FROM Types WHERE name = 'High school'), 'processed',
  'Year 9 camp is on 20 November. Please pay the camp deposit by 1 November. Details: https://princeshill.example/camp',
  NULL, 'Year 9 camp on 20 November; deposit due 1 November.', 'https://princeshill.example/camp', NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, TRUE, 'Pay the camp deposit by 1 November', NULL, FALSE,
  TIMESTAMP '2026-10-21 16:20:00', TIMESTAMP '2026-10-21 17:00:00', FALSE,
  TIMESTAMP '2026-10-21 17:00:00', TIMESTAMP '2026-10-21 17:00:00', NULL),
('payslip-1@employer.example', 'tmp-104', 104, 1, 'INBOX', 'Payslip for October',
  'payroll@employer.example', 'Payroll', (SELECT id FROM Types WHERE name = 'Payslip'), 'processed',
  'Your payslip for October is attached.', NULL, 'October payslip attached.', NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, NULL, TRUE,
  TIMESTAMP '2026-10-15 17:00:00', TIMESTAMP '2026-10-15 18:00:00', FALSE,
  TIMESTAMP '2026-10-15 18:00:00', TIMESTAMP '2026-10-15 18:00:00', NULL),
('sale-1@shop.example', 'tmp-105', 105, 1, 'INBOX', 'Spring sale starts now',
  'news@shop.example', 'Shop', NULL, 'processed',
  'Everything is 20% off this weekend.', NULL, 'A spring sale with 20% off.', NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, NULL, FALSE,
  TIMESTAMP '2026-10-19 12:00:00', TIMESTAMP '2026-10-19 13:00:00', FALSE,
  TIMESTAMP '2026-10-19 13:00:00', TIMESTAMP '2026-10-19 13:00:00', NULL),
('broken-1@example.test', 'tmp-106', 106, 1, 'INBOX', 'Unreadable message',
  'someone@example.test', NULL, NULL, 'failed',
  'Garbled', NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, NULL, FALSE,
  TIMESTAMP '2026-10-18 07:45:00', TIMESTAMP '2026-10-18 08:00:00', FALSE,
  TIMESTAMP '2026-10-18 08:00:00', TIMESTAMP '2026-10-18 08:00:00', NULL),
('autoreply-1@example.test', 'tmp-107', 107, 1, 'INBOX', 'Automatic reply',
  'noreply@example.test', NULL, NULL, 'skipped',
  'I am out of the office.', NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, NULL, FALSE,
  TIMESTAMP '2026-10-17 11:00:00', TIMESTAMP '2026-10-17 11:30:00', FALSE,
  TIMESTAMP '2026-10-17 11:30:00', TIMESTAMP '2026-10-17 11:30:00', NULL),
('deleted-1@example.test', 'tmp-108', 108, 1, 'Trash', 'Deleted email',
  'spam@example.test', NULL, NULL, 'processed',
  'Unwanted', NULL, 'Unwanted mail.', NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, 'Trash', FALSE,
  TIMESTAMP '2026-10-16 10:00:00', TIMESTAMP '2026-10-16 10:30:00', TRUE,
  TIMESTAMP '2026-10-16 10:30:00', TIMESTAMP '2026-10-16 11:00:00', TIMESTAMP '2026-10-16 11:00:00'),
('pending-1@example.test', 'tmp-109', 109, 1, 'INBOX', 'Still processing',
  'someone@example.test', NULL, NULL, 'pending',
  'Hello', NULL, NULL, NULL, NULL, NULL,
  NULL, NULL, NULL, NULL, NULL,
  FALSE, FALSE, NULL, NULL, FALSE,
  TIMESTAMP '2026-10-22 06:00:00', NULL, FALSE,
  TIMESTAMP '2026-10-22 06:00:00', TIMESTAMP '2026-10-22 06:00:00', NULL);

INSERT INTO Emails (message_id, message_hash, imap_uid, uid_validity, folder, subject, sender, sender_name, type_id, status,
  message, summary, response_required, action_required, is_read, received_at, processed_at, deleted,
  created_at, last_updated_at)
SELECT CONCAT('update-', X, '@club.example'), CONCAT('tmp-', 1000 + X), 1000 + X, 1, 'INBOX', CONCAT('Weekly update ', X),
  'updates@club.example', 'Club', NULL, 'processed',
  CONCAT('News for week ', X), CONCAT('Club news, week ', X), FALSE, FALSE, TRUE,
  DATEADD('HOUR', X, TIMESTAMP '2026-09-01 00:00:00'), DATEADD('HOUR', X + 1, TIMESTAMP '2026-09-01 00:00:00'), FALSE,
  DATEADD('HOUR', X + 1, TIMESTAMP '2026-09-01 00:00:00'), DATEADD('HOUR', X + 1, TIMESTAMP '2026-09-01 00:00:00')
FROM SYSTEM_RANGE(1, 60);

-- message_hash is the lower-case hex SHA-256 of the UTF-8 Message-ID (docs/dev/contracts.md).
-- The rows above were inserted with temporary unique values; H2 computes the real ones.

UPDATE Emails SET message_hash = LOWER(RAWTOHEX(HASH('SHA-256', STRINGTOUTF8(message_id))));

-- Events: one added to a calendar, one waiting for a calendar (all day)

INSERT INTO Email_events (email_id, calendar_id, title, starts_at, ends_at, all_day, location, notes, is_added, khal_uid,
  created_at, last_updated_at) VALUES
((SELECT id FROM Emails WHERE message_id = 'excursion-1@brunswicksouth.example'),
  (SELECT id FROM Calendars WHERE name = 'Family-and-Kids'),
  'Zoo excursion', TIMESTAMP '2026-11-05 09:00:00', TIMESTAMP '2026-11-05 15:00:00', FALSE, 'Melbourne Zoo',
  'Bring a packed lunch', TRUE, 'test-uid-zoo-excursion',
  TIMESTAMP '2026-10-21 09:00:00', TIMESTAMP '2026-10-21 09:00:00'),
((SELECT id FROM Emails WHERE message_id = 'excursion-1@brunswicksouth.example'), NULL,
  'Parent teacher interviews', TIMESTAMP '2026-11-12 00:00:00', NULL, TRUE, NULL,
  NULL, FALSE, NULL,
  TIMESTAMP '2026-10-21 09:00:00', TIMESTAMP '2026-10-21 09:00:00'),
((SELECT id FROM Emails WHERE message_id = 'camp-1@princeshill.example'), NULL,
  'Year 9 camp', TIMESTAMP '2026-11-20 00:00:00', NULL, TRUE, 'Anglesea',
  NULL, FALSE, NULL,
  TIMESTAMP '2026-10-21 17:00:00', TIMESTAMP '2026-10-21 17:00:00');

-- Attachments: downloaded PDFs and one attachment that was not downloaded

INSERT INTO Email_attachments (email_id, filename, content_type, size_bytes, part_path, stored_path, downloaded_at,
  created_at, last_updated_at) VALUES
((SELECT id FROM Emails WHERE message_id = 'bill-1@electriclight.example'), 'bill-october.pdf', 'application/pdf', 52000,
  '2', 'known-data/bill-october.pdf', TIMESTAMP '2026-10-20 10:00:00',
  TIMESTAMP '2026-10-20 10:00:00', TIMESTAMP '2026-10-20 10:00:00'),
((SELECT id FROM Emails WHERE message_id = 'bill-1@electriclight.example'), 'logo.png', 'image/png', 4000,
  '3', NULL, NULL,
  TIMESTAMP '2026-10-20 10:00:00', TIMESTAMP '2026-10-20 10:00:00'),
((SELECT id FROM Emails WHERE message_id = 'payslip-1@employer.example'), 'payslip-2026-10.pdf', 'application/pdf', 31000,
  '2', 'known-data/payslip-2026-10.pdf', TIMESTAMP '2026-10-15 18:00:00',
  TIMESTAMP '2026-10-15 18:00:00', TIMESTAMP '2026-10-15 18:00:00');

-- Matched rules and the actions they ran

INSERT INTO Email_rule_matches (email_id, rule_id, rule_type, position, matched_at)
SELECT e.id, r.id, r.rule_type, m.position, e.processed_at
FROM (VALUES
  ('bill-1@electriclight.example', 'Bills by subject', 0),
  ('bill-1@electriclight.example', 'Pay bills', 1),
  ('excursion-1@brunswicksouth.example', 'School newsletters', 0),
  ('excursion-1@brunswicksouth.example', 'School events', 1),
  ('payslip-1@employer.example', 'Payslips', 0),
  ('payslip-1@employer.example', 'Forward payslips', 1)
) AS m (message_id, rule_name, position)
JOIN Emails e ON e.message_id = m.message_id
JOIN Rules r ON r.name = m.rule_name;

INSERT INTO Email_action_results (email_id, rule_id, rule_action_id, action_type, source, status, detail, executed_at)
SELECT e.id, r.id, ra.id, a.action_type, 'rule', a.status, a.detail, e.processed_at
FROM (VALUES
  ('bill-1@electriclight.example', 'Bills by subject', 'set_type', 'done', 'Bill'),
  ('bill-1@electriclight.example', 'Pay bills', 'process_bill', 'done', 'Reminder added to Bills'),
  ('bill-1@electriclight.example', 'Pay bills', 'move', 'done', 'Folders/Bills'),
  ('excursion-1@brunswicksouth.example', 'School newsletters', 'set_type', 'done', 'Primary school'),
  ('excursion-1@brunswicksouth.example', 'School newsletters', 'check_for_events', 'done', '2 events found'),
  ('excursion-1@brunswicksouth.example', 'School events', 'create_events', 'done', '1 event added to Family-and-Kids'),
  ('excursion-1@brunswicksouth.example', 'School events', 'set_reply_needed', 'done', NULL),
  ('payslip-1@employer.example', 'Payslips', 'set_type', 'done', 'Payslip'),
  ('payslip-1@employer.example', 'Payslips', 'download_attachments', 'done', '1 attachment'),
  ('payslip-1@employer.example', 'Forward payslips', 'forward_email', 'done', 'accountant@example.test'),
  ('payslip-1@employer.example', 'Forward payslips', 'mark_read', 'done', NULL)
) AS a (message_id, rule_name, action_type, status, detail)
JOIN Emails e ON e.message_id = a.message_id
JOIN Rules r ON r.name = a.rule_name
JOIN Rule_actions ra ON ra.rule_id = r.id AND ra.action_type = a.action_type AND ra.deleted = FALSE;

-- Errors: a failed email with two, and a processed email with one

INSERT INTO Email_errors (email_id, phase, message, created_at)
SELECT e.id, x.phase, x.message, e.processed_at
FROM (VALUES
  ('broken-1@example.test', 'llm', 'LLM request timed out after 2m'),
  ('broken-1@example.test', 'action', 'Move failed: folder Folders/Missing does not exist'),
  ('camp-1@princeshill.example', 'fetch', 'Could not fetch https://princeshill.example/camp: HTTP 404')
) AS x (message_id, phase, message)
JOIN Emails e ON e.message_id = x.message_id;

-- Processing runs

INSERT INTO Processing_runs (trigger_source, status, started_at, finished_at, emails_found, emails_processed, emails_failed, error) VALUES
('schedule', 'succeeded', TIMESTAMP '2026-10-22 05:00:00', TIMESTAMP '2026-10-22 05:02:00', 3, 3, 0, NULL),
('manual', 'failed', TIMESTAMP '2026-10-21 12:00:00', TIMESTAMP '2026-10-21 12:00:05', 0, 0, 0, 'Could not connect to Dovecot');
