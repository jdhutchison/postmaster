-- Initial types (REQUIREMENTS.md, Types item 9). Bill is a system type with the fixed key `bill`:
-- the code finds it by key, so it can be renamed but not deleted (CLARIFICATIONS.md Q16).
-- name_key is the lower-cased name, used for case-insensitive uniqueness (D20).
-- The colours are from the Dracula palette and can be changed in the UI.

INSERT INTO Types (system_key, name, name_key, description, colour, deleted, created_at, last_updated_at) VALUES
('bill', 'Bill', 'bill', 'A utility bill', '#ff5555', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Primary school', 'primary school', 'Message from Brunswick South', '#50fa7b', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'High school', 'high school', 'Message from Princes Hill SC', '#8be9fd', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Statement notification', 'statement notification', 'Statement available for card or bank', '#bd93f9', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Legal', 'legal', NULL, '#ffb86c', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Caloundra West', 'caloundra west', 'Message about property', '#f1fa8c', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Ebay', 'ebay', 'Ebay business', '#ff79c6', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Payslip', 'payslip', 'Payslips', '#6272a4', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(NULL, 'Showfit', 'showfit', 'Message about Jeremy''s dancing', '#9580ff', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
