-- V2__tasks.sql
-- Unified background-task ledger (P2-TASKLEDGER, ULTRAPLAN section 7.2): one row per engine-initiated
-- task (a cron fire, a sub-agent spawn, other background work), written through the TaskExecutor sink.
-- Operators query it via direct SQL (no query DSL in v0.5). Timestamps are milliseconds since epoch
-- (INTEGER), matching V1. Flyway-managed and forward-only; Hibernate never owns this schema.

CREATE TABLE tasks (
  id            TEXT PRIMARY KEY,          -- UUID string, supplied by the recorder
  agent_id      TEXT NOT NULL,
  task_type     TEXT NOT NULL,             -- cron | sub_agent | background
  cron_id       TEXT,                      -- set for a cron task, else null
  sub_agent_id  TEXT,                      -- set for a sub_agent task, else null
  name          TEXT NOT NULL,
  scheduled_for INTEGER,
  started_at    INTEGER,
  completed_at  INTEGER,
  status        TEXT NOT NULL,             -- pending | running | completed | error
  result        TEXT,                      -- JSON or text
  error         TEXT,
  duration_ms   INTEGER,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_tasks_agent  ON tasks(agent_id, created_at);
CREATE INDEX idx_tasks_status ON tasks(status, created_at);
