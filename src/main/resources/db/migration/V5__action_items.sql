-- Action items and the to-do list (CLARIFICATIONS.md R5). An email can need several things done;
-- each is an action item. update_todo_list writes open items to a markdown to-do list, and
-- todo_name is the item's file name there (without .md) once it has been written.
-- Emails.action_required and action_description stay, kept in step with the open items.

CREATE TABLE Email_action_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email_id BIGINT NOT NULL,
  position INT NOT NULL DEFAULT 0,
  description VARCHAR(500) NOT NULL,
  due_date DATE NULL,
  notes TEXT NULL,
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  completed_at DATETIME(6) NULL,
  todo_name VARCHAR(255) NULL,
  todo_created_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  last_updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_email_action_items_email FOREIGN KEY (email_id) REFERENCES Emails (id) ON DELETE CASCADE
) ${table_options};

CREATE INDEX idx_email_action_items_open ON Email_action_items (completed, todo_name);

-- The single action an email could have before becomes its first item
INSERT INTO Email_action_items (email_id, position, description, completed, created_at, last_updated_at)
SELECT id, 0, LEFT(COALESCE(action_description, 'The email asks you to do something.'), 500), FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM Emails WHERE action_required = TRUE;

INSERT INTO Settings (setting_key, label, setting_value, value_type, allowed_values, description, position, created_at, last_updated_at) VALUES
('todo.path', 'To-do list folder', '/data/todo', 'path', NULL,
  'Folder of the markdown to-do list that update_todo_list writes to. Each action item gets a file here, and index.md lists them above its <!-- todo-items:end --> line.', 800, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('todo.postmaster_url', 'Postmaster address', NULL, 'text', NULL,
  'Address of these pages, e.g. http://tigerbear:8080/mail, for the links from to-do items back to their email. Leave empty for no links.', 810, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- The summarise prompt asks for a list of action items, each with an optional due date and notes,
-- and gets the received date to work deadlines out from. A prompt that has been edited keeps its
-- text where it no longer matches; its single action still becomes one item.
UPDATE Prompts SET contents = REPLACE(contents,
'  "action_required": <true|false>,
  "action": "<short description of what needs to be done, including any deadline>",',
'  "action_items": [
    {
      "action": "<short description of one thing that needs to be done, phrased as a task>",
      "due": "<the deadline as a date, yyyy-mm-dd, if one is given or can be worked out from when the email was received, leave null otherwise>",
      "notes": "<string> details from the email needed to do it"
    }
  ],')
WHERE prompt_key = 'summarise';

UPDATE Prompts SET contents = REPLACE(contents,
'or do something by a deadline. Return the details',
'or do something by a deadline. List each separate thing to be done as its own action item. Return the details')
WHERE prompt_key = 'summarise';

UPDATE Prompts SET contents = REPLACE(contents,
'Sender: {{sender}}
Subject: {{subject}}
Contents: {{text}}',
'Received: {{received}}
Sender: {{sender}}
Subject: {{subject}}
Contents: {{text}}')
WHERE prompt_key = 'summarise';
