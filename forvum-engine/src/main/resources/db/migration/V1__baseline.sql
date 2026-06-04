-- V1__baseline.sql
-- Forvum operational schema, Flyway-managed and forward-only (ULTRAPLAN section 4.2).
-- Seven tables; timestamps are milliseconds since epoch (stored as INTEGER) for cheap aggregation.
-- Hibernate never owns this schema (quarkus.hibernate-orm.schema-management.strategy=none); Flyway does.

-- Sessions: one per channel conversation (tui per-invocation, web per-socket, telegram per-chat)
CREATE TABLE sessions (
  id            TEXT PRIMARY KEY,
  identity_id   TEXT NOT NULL,
  channel_id    TEXT NOT NULL,
  agent_id      TEXT NOT NULL,
  started_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  metadata_json TEXT
);
CREATE INDEX idx_sessions_identity ON sessions(identity_id);
CREATE INDEX idx_sessions_lastseen ON sessions(last_seen_at);

-- Messages: append-only chat history, one row per turn-level message
CREATE TABLE messages (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  agent_id    TEXT NOT NULL,
  role        TEXT NOT NULL,     -- user | assistant | system | tool
  content     TEXT NOT NULL,
  tokens      INTEGER,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_messages_session ON messages(session_id, created_at);
CREATE INDEX idx_messages_agent   ON messages(agent_id, created_at);

-- Episodic memory: per-agent, per-session event log for the agent's own recall
CREATE TABLE episodic_memory (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id    TEXT NOT NULL,
  session_id  TEXT NOT NULL,
  event_type  TEXT NOT NULL,     -- observation | decision | reflection
  content     TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_episodic_agent_session ON episodic_memory(agent_id, session_id, created_at);

-- Semantic memory: embedded long-term facts the agent has chosen to keep
CREATE TABLE semantic_memory (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id    TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT NOT NULL,
  embedding   BLOB,              -- float32 vector, length defined by embedding model
  source      TEXT,              -- free-form provenance
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  UNIQUE(agent_id, key)
);
CREATE INDEX idx_semantic_agent ON semantic_memory(agent_id, updated_at);

-- Tool invocations: every tool call, inputs, outputs, outcome
CREATE TABLE tool_invocations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  agent_id    TEXT NOT NULL,
  tool_name   TEXT NOT NULL,
  arguments   TEXT NOT NULL,     -- JSON
  result      TEXT,              -- JSON or truncated text
  status      TEXT NOT NULL,     -- ok | error | denied
  latency_ms  INTEGER,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_tool_session ON tool_invocations(session_id, created_at);
CREATE INDEX idx_tool_agent   ON tool_invocations(agent_id, created_at);

-- Provider calls: every LLM call, model, tokens, cost, fallback flag
CREATE TABLE provider_calls (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  provider     TEXT NOT NULL,
  model        TEXT NOT NULL,
  tokens_in    INTEGER NOT NULL,
  tokens_out   INTEGER NOT NULL,
  cost_usd     REAL,
  latency_ms   INTEGER NOT NULL,
  is_fallback  INTEGER NOT NULL DEFAULT 0,  -- 1 if this call only happened because an earlier chain entry failed
  error        TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_provider_session  ON provider_calls(session_id, created_at);
CREATE INDEX idx_provider_agent    ON provider_calls(agent_id, created_at);
CREATE INDEX idx_provider_fallback ON provider_calls(is_fallback, created_at);

-- CAPR events: per-turn pass/fail verdict from the judge model
CREATE TABLE capr_events (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  turn_id      INTEGER NOT NULL,   -- references messages.id of the assistant reply
  passed       INTEGER NOT NULL,   -- 0 or 1
  judge_model  TEXT NOT NULL,
  rationale    TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_capr_agent ON capr_events(agent_id, created_at);
