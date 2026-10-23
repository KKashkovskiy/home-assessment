-- Story scenarios bring k trials per scenario and three gates. The run row carries what the report prints: pass^k,
-- the Wilson interval on the per-trial pass rate (null when nothing was judged), and each gate's verdict, so a reader
-- needs no report.md. The counts above count trials from now on. Rows from before ran one trial each and had no
-- gates, hence the defaults.
ALTER TABLE eval_run ADD COLUMN trials        INT NOT NULL DEFAULT 1;   -- trials per scenario
ALTER TABLE eval_run ADD COLUMN pass_k        DECIMAL(5,4);             -- share of scenarios whose every trial passed
ALTER TABLE eval_run ADD COLUMN wilson_low    DECIMAL(5,4);
ALTER TABLE eval_run ADD COLUMN wilson_high   DECIMAL(5,4);
ALTER TABLE eval_run ADD COLUMN gate_hard     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE eval_run ADD COLUMN gate_global   BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE eval_run ADD COLUMN gate_validity BOOLEAN NOT NULL DEFAULT TRUE;
