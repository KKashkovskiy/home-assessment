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
  status                   VARCHAR(20)  NOT NULL,  -- ACTIVE
  stage                    VARCHAR(20)  NOT NULL,  -- Stage name, ENGAGE at start
  flags_json               CLOB         NOT NULL,  -- JSON array of Flag names
  goal_met                 BOOLEAN      NOT NULL DEFAULT FALSE,
  unsubscribed             BOOLEAN      NOT NULL DEFAULT FALSE,
  ended                    BOOLEAN      NOT NULL DEFAULT FALSE,
  needs_human              BOOLEAN      NOT NULL DEFAULT FALSE,
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
