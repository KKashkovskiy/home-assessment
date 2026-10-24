-- pass^k is clean scenarios over judged ones, so the Analytics compare pane needs those counts as its numerator and
-- base: the trial counts above cannot rebuild them. Null on rows from before this migration, which drop out of the pane.
ALTER TABLE eval_run ADD COLUMN judged_scenarios INT;   -- scenarios with a PASS or FAIL trial
ALTER TABLE eval_run ADD COLUMN clean_scenarios  INT;   -- judged scenarios with no FAIL trial
