-- V2__compaction.sql
-- Session compaction (P2-COMPACT, ULTRAPLAN section 7.2 item 20; CE Compress). Forward-only.
-- Adds the three columns prefix-preserving compaction needs; no existing rows are mutated.
--
-- NOTE: the package brief named this V3, but V1 is the only prior migration in this repo, so a V3
-- would leave a V2 gap. The migration chain is kept contiguous at V2 (Flyway version = 2).

-- The highest messages.id that belongs to the cached prompt prefix. NULL until first compaction;
-- compaction never mutates a message with id <= cached_prefix_end_index (prompt-cache stability).
ALTER TABLE sessions ADD COLUMN cached_prefix_end_index INTEGER;

-- Structural block discriminator (ai.forvum.core.BlockType): turn_message | turn_reasoning |
-- turn_artifact | tool_execution. Every existing row backfills to 'turn_message' (the v0.1 default),
-- so the normal conversational path is unchanged.
ALTER TABLE messages ADD COLUMN block_type TEXT NOT NULL DEFAULT 'turn_message';
CREATE INDEX idx_messages_blocktype ON messages(session_id, block_type, id);

-- CAPR rows are append-only; compaction archives (never deletes) verdicts whose turn was compacted out.
ALTER TABLE capr_events ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_capr_archived ON capr_events(is_archived, created_at);
