-- conversation.source is in the baseline already, API on every row so far; the console writes SIM from here on.
-- The console lists conversations newest activity first, so updated_at gets an index.
CREATE INDEX idx_conversation_updated ON conversation (updated_at DESC);
