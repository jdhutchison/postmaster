-- Whether a rule's conditions must all match (AND) or any one of them (OR) (CLARIFICATIONS.md R8).
-- Existing rules keep AND.
ALTER TABLE Rules ADD COLUMN condition_join VARCHAR(3) NOT NULL DEFAULT 'all';
