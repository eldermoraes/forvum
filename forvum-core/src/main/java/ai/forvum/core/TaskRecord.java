package ai.forvum.core;

import ai.forvum.core.id.AgentId;

/**
 * One {@code tasks} ledger row — the unified record of an engine-initiated background task (a cron fire,
 * a sub-agent spawn, or other background work), written through the {@code TaskExecutor} sink (ULTRAPLAN
 * section 7.2 P2-TASKLEDGER). The write contract is one immutable record per terminal task outcome;
 * operators query the ledger via direct SQL (no query DSL in v0.5).
 *
 * <p>Layer-0 record: validated in its canonical constructor and registered for GraalVM native reflection
 * from {@code forvum-engine}'s {@code CoreReflectionRegistration} (it cannot carry
 * {@code @RegisterForReflection} — {@code forvum-core} bans {@code io.quarkus*}, CLAUDE.md section 5).
 *
 * <p>Timestamps are milliseconds since epoch. {@code scheduledFor}/{@code startedAt}/{@code completedAt}
 * are nullable {@link Long} (a task may be recorded with only some lifecycle points known);
 * {@code createdAt} is always present. {@code cronId}/{@code subAgentId} are the optional provenance keys
 * — exactly one is set for a {@link TaskType#CRON}/{@link TaskType#SUB_AGENT} row respectively.
 * {@code result}/{@code error}/{@code durationMs} are null when not applicable.
 *
 * @param id           the task id (a UUID string, the {@code tasks.id} TEXT primary key)
 * @param agentId      the agent the task runs as
 * @param taskType     the kind of task ({@code cron} | {@code sub_agent} | {@code background})
 * @param cronId       the originating cron id (set for a {@code CRON} task, else null)
 * @param subAgentId   the spawned child agent id (set for a {@code SUB_AGENT} task, else null)
 * @param name         a short human-readable label for the task
 * @param scheduledFor when the task was scheduled to run (millis since epoch, nullable)
 * @param startedAt    when the task started running (millis since epoch, nullable)
 * @param completedAt  when the task reached a terminal state (millis since epoch, nullable)
 * @param status       the task lifecycle state
 * @param result       the task result (JSON or text, nullable)
 * @param error        the failing exception's message/FQCN (set for an {@code ERROR} task, else null)
 * @param durationMs   the run duration in milliseconds (nullable)
 * @param createdAt    when this ledger row was written (millis since epoch, always present)
 */
public record TaskRecord(
        String id,
        AgentId agentId,
        TaskType taskType,
        String cronId,
        String subAgentId,
        String name,
        Long scheduledFor,
        Long startedAt,
        Long completedAt,
        TaskStatus status,
        String result,
        String error,
        Long durationMs,
        long createdAt) {

    public TaskRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("TaskRecord id must be a non-blank task id.");
        }
        if (agentId == null) {
            throw new IllegalStateException("TaskRecord '" + id + "' agentId must be non-null.");
        }
        if (taskType == null) {
            throw new IllegalStateException("TaskRecord '" + id + "' taskType must be non-null.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("TaskRecord '" + id + "' name must be non-blank.");
        }
        if (status == null) {
            throw new IllegalStateException("TaskRecord '" + id + "' status must be non-null.");
        }
    }
}
