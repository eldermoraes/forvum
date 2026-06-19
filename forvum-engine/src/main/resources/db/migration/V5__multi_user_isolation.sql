-- #53 multi-user isolation: semantic_memory becomes per-IDENTITY, not just per-agent, so two users of the
-- same agent get isolated long-term facts. SQLite cannot ALTER a UNIQUE constraint, so the table is
-- recreated with an identity_id column and UNIQUE(identity_id, agent_id, key). The DEFAULT 'default' makes
-- a single-user (multiUser-off) deployment byte-identical: every existing fact migrates to identity
-- 'default', and the 'default' identity is also the shared team-skill namespace every user reads.

CREATE TABLE semantic_memory_new (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  identity_id TEXT NOT NULL DEFAULT 'default',
  agent_id    TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT NOT NULL,
  embedding   BLOB,              -- float32 vector, length defined by embedding model
  source      TEXT,              -- free-form provenance
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  UNIQUE(identity_id, agent_id, key)
);

INSERT INTO semantic_memory_new (id, identity_id, agent_id, key, value, embedding, source, created_at, updated_at)
  SELECT id, 'default', agent_id, key, value, embedding, source, created_at, updated_at FROM semantic_memory;

DROP TABLE semantic_memory;
ALTER TABLE semantic_memory_new RENAME TO semantic_memory;

CREATE INDEX idx_semantic_agent ON semantic_memory(agent_id, updated_at);
CREATE INDEX idx_semantic_identity ON semantic_memory(identity_id, agent_id, updated_at);
