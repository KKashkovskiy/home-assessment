-- One row per eval run, written by the harness into the app database when the run finishes: counts and the
-- report path, never transcripts, since the run's conversations live in its own database under evals/runs/<id>.
-- The Analytics compare pane reads the latest keyed run per prompt version.
CREATE TABLE eval_run (
  id              VARCHAR(40) PRIMARY KEY,         -- the run id, also its directory under evals/runs
  started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at     TIMESTAMP WITH TIME ZONE,
  prompt_version  VARCHAR(20) NOT NULL,            -- the active version the run's conversations pinned
  model           VARCHAR(64) NOT NULL,
  offline         BOOLEAN NOT NULL,                -- the stand-in model: proves the harness, not the prompt
  selected        INT NOT NULL,
  passed          INT NOT NULL,
  failed          INT NOT NULL,
  invalid         INT NOT NULL DEFAULT 0,
  skipped         INT NOT NULL,
  verdict         VARCHAR(8) NOT NULL,             -- PASS | FAIL | SKIPPED
  cost_usd        DECIMAL(10,4) NOT NULL,
  report_path     VARCHAR(255) NOT NULL
);
