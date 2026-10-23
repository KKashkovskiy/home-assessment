-- Analytics reads a conversation's opt-out reason from its lead's suppression row, per conversation in range,
-- and the conversation list's opt-out filter does the same.
CREATE INDEX suppression_lead_idx ON suppression (lead_id);
