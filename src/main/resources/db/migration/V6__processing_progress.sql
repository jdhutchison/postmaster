-- Processing in saved steps (CLARIFICATIONS.md R6). An email is saved before anything is done
-- with it, and again after each step, so an interrupted email can be carried on from where it
-- stopped. progress is the last step saved: recorded, classified, summarised or matched; NULL once
-- processing has finished. attempts counts the passes started, so an email that keeps failing is
-- given up on.

ALTER TABLE Emails ADD COLUMN progress VARCHAR(20) NULL;
ALTER TABLE Emails ADD COLUMN attempts INT NOT NULL DEFAULT 0;
