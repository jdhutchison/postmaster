-- Thresholds for the status endpoint (CLARIFICATIONS.md R7): past them it reports DEGRADED.
INSERT INTO Settings (setting_key, label, setting_value, value_type, allowed_values, description, position, created_at, last_updated_at) VALUES
('health.pending_limit', 'Pending email warning', '25', 'int', NULL,
  'The status endpoint reports DEGRADED when more emails than this are waiting to be processed, e.g. while the LLM is down.', 900, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('health.min_free_mb', 'Low disk space warning (MiB)', '1024', 'int', NULL,
  'The status endpoint reports DEGRADED when the disk holding attachments, the to-do list or the calendars has less free space than this.', 910, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
