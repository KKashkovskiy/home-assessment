-- LLM judges answer advisory questions beside the gates. The run row records which model judged (null when no judge
-- was asked) and what the judges cost, a line apart from cost_usd, which stays the agent and the simulated lead.
ALTER TABLE eval_run ADD COLUMN judge_model    VARCHAR(80);
ALTER TABLE eval_run ADD COLUMN judge_cost_usd DECIMAL(10,5) DEFAULT 0 NOT NULL;
