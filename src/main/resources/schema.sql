-- Schema for the embedded H2 file database. Every statement is IF NOT EXISTS so boot is idempotent.
-- A changed column is not picked up by an existing database: delete data/ after pulling a schema change.

CREATE TABLE IF NOT EXISTS lead (
  id               VARCHAR(40)  PRIMARY KEY,
  given_name       VARCHAR(80),
  phone            VARCHAR(20)  NOT NULL,          -- E.164, +61...
  email            VARCHAR(200),
  state            VARCHAR(3),                     -- NSW ACT VIC QLD SA WA TAS NT or null
  current_provider VARCHAR(40),                    -- Bupa HBF Medibank nib HCF or null for none
  current_premium  VARCHAR(40),                    -- "$350-$450" or null
  consent_source   VARCHAR(200),                   -- stored verbatim, optional
  consent_at       TIMESTAMP WITH TIME ZONE,       -- optional
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation (
  id                       VARCHAR(40)  PRIMARY KEY,
  lead_id                  VARCHAR(40)  NOT NULL REFERENCES lead(id),
  status                   VARCHAR(20)  NOT NULL,  -- ACTIVE, HANDOFF, or the end reason once ended
  stage                    VARCHAR(20)  NOT NULL,  -- Stage name, ENGAGE at start
  flags_json               CLOB         NOT NULL,  -- JSON array of Flag names
  goal_met                 BOOLEAN      NOT NULL DEFAULT FALSE,
  unsubscribed             BOOLEAN      NOT NULL DEFAULT FALSE,
  ended                    BOOLEAN      NOT NULL DEFAULT FALSE,
  needs_human              BOOLEAN      NOT NULL DEFAULT FALSE,
  end_reason               VARCHAR(20)  NOT NULL,  -- EndReason name, NONE while open
  end_reason_history_json  CLOB         NOT NULL,  -- JSON array of {reason, at}, oldest first
  handoff_reason           VARCHAR(20)  NOT NULL,  -- HandoffReason name, the latest; NONE until a handoff
  lead_zone_id             VARCHAR(40)  NOT NULL,  -- Australia/Melbourne etc., fixed at start
  prompt_version           VARCHAR(20)  NOT NULL,  -- v1
  source                   VARCHAR(10)  NOT NULL,  -- API
  created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
  version                  BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS conversation_lead_idx ON conversation(lead_id, status);

CREATE TABLE IF NOT EXISTS message (
  id                  VARCHAR(40) PRIMARY KEY,
  conversation_id     VARCHAR(40) NOT NULL REFERENCES conversation(id),
  turn_no             INT         NOT NULL,          -- 0 for the opener
  direction           VARCHAR(3)  NOT NULL,          -- IN | OUT
  text                CLOB        NOT NULL,
  char_count          INT         NOT NULL,
  provider_message_id VARCHAR(80),                   -- the SMS platform's id for inbound; null for outbound
  source              VARCHAR(10),                   -- TEMPLATE | LLM | FALLBACK | FIXED; null for IN
  repaired            BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS message_provider_idx ON message(conversation_id, provider_message_id);

CREATE TABLE IF NOT EXISTS turn (
  id                  VARCHAR(40) PRIMARY KEY,
  conversation_id     VARCHAR(40) NOT NULL REFERENCES conversation(id),
  turn_no             INT         NOT NULL,
  inbound_message_id  VARCHAR(40) NOT NULL REFERENCES message(id),
  status              VARCHAR(10) NOT NULL,          -- OK | NO_REPLY | REPAIRED | FALLBACK | ERROR
  response_json       CLOB        NOT NULL,          -- the response returned; replayed on a duplicate
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS turn_no_idx ON turn(conversation_id, turn_no);

CREATE TABLE IF NOT EXISTS suppression (
  phone       VARCHAR(20) PRIMARY KEY,               -- E.164
  reason      VARCHAR(10) NOT NULL,                  -- KEYWORD | MODEL
  lead_id     VARCHAR(40),
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- What each turn's model call returned and cost; zeros and nulls for a turn that made none.
ALTER TABLE turn ADD COLUMN IF NOT EXISTS decision_json CLOB;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS model VARCHAR(60);
ALTER TABLE turn ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(20);
ALTER TABLE turn ADD COLUMN IF NOT EXISTS tokens_in INT DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS tokens_cached INT DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS tokens_out INT DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS tokens_reasoning INT DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS cost_usd DECIMAL(10,6) DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS latency_ms INT DEFAULT 0 NOT NULL;
ALTER TABLE turn ADD COLUMN IF NOT EXISTS reasoning_summary CLOB;     -- never sent to the lead
ALTER TABLE turn ADD COLUMN IF NOT EXISTS raw_request_json CLOB;      -- the SDK's request body; the key is a header
ALTER TABLE turn ADD COLUMN IF NOT EXISTS raw_response_json CLOB;

CREATE TABLE IF NOT EXISTS trace_event (
  id              VARCHAR(40) PRIMARY KEY,
  conversation_id VARCHAR(40) NOT NULL REFERENCES conversation(id),
  turn_no         INT         NOT NULL,
  seq             INT         NOT NULL,              -- order within the turn, from 1
  type            VARCHAR(20) NOT NULL,              -- GUARD | MODEL_CALL | TOOL_CALL | STATE_DIFF | ERROR
  name            VARCHAR(60) NOT NULL,
  outcome         VARCHAR(10) NOT NULL,              -- PASS | BLOCK | OVERRIDE | REPAIR | INFO
  payload_json    CLOB,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT trace_event_seq_uq UNIQUE (conversation_id, turn_no, seq)
);

-- What a conversation started on, and what the model has gathered about the lead.
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS model VARCHAR(60);
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(10);
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS app_version VARCHAR(20);
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS extracted_json CLOB;   -- JSON object of known facts
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS summary VARCHAR(200);  -- the model's latest line for the advisor

-- Counters only code moves, and the turn whose messages first carried health information.
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS objections_used INT DEFAULT 0 NOT NULL;
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS off_topic_streak INT DEFAULT 0 NOT NULL;
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS agent_turns INT DEFAULT 0 NOT NULL;            -- messages sent after the opener
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS post_terminal_replies INT DEFAULT 0 NOT NULL;  -- messages sent on or after the ending turn
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS health_info_turn INT;  -- null until HEALTH_INFO; bodies from it on are hidden
CREATE INDEX IF NOT EXISTS turn_cost_idx ON turn(conversation_id, cost_usd);   -- the spend caps sum it
