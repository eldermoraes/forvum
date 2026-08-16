-- #195: real per-turn judge verdicts in capr_events.
-- `model` attributes the verdict to the model that answered the turn (the ModelRef canonical
-- "provider:model" form, resolved from the last successful provider_calls row of the turn's session);
-- `score` is the normalized [0,1] verdict score. Both are NULL on rows written with the judge disabled
-- (the neutral passed row) or when the judge itself was unavailable, so per-model health aggregation
-- (ModelHealthReader) only ever tallies genuinely attributed verdicts.
ALTER TABLE capr_events ADD COLUMN model TEXT;
ALTER TABLE capr_events ADD COLUMN score REAL;
CREATE INDEX idx_capr_model ON capr_events(agent_id, model, created_at);
