-- Kinds of action item (CLARIFICATIONS.md R11): action (something the email asks for, found by the
-- LLM), reply (replying to an email that needs a response) or task (added by hand). Reply items
-- follow the email's response required flag rather than its action required flag.
ALTER TABLE Email_action_items ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'action';
