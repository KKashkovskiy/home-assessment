-- One row per prompt version: the five artifacts and their bookkeeping. Content columns are written once, by
-- an insert; active is the only column that changes, and exactly one row holds it.
CREATE TABLE prompt_version (
  id             VARCHAR(20)  PRIMARY KEY,           -- v1, v2, ...
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by     VARCHAR(40)  NOT NULL,              -- repo for a seeded prompts/vN/, console for a save
  note           VARCHAR(200),
  active         BOOLEAN      NOT NULL DEFAULT FALSE,
  prompt_md      CLOB         NOT NULL,
  knowledge_md   CLOB         NOT NULL,
  schema_json    CLOB         NOT NULL,
  tools_json     CLOB         NOT NULL,
  templates_json CLOB         NOT NULL               -- {"OPENER_PROVIDER": "Hi{name_part}, ...", ...}
);
-- conversation.prompt_version is the pin and already exists. No foreign key: a database baselined from
-- schema.sql has conversations on v1 before the seed inserts the v1 row.
