package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the {@code tasks} table (V2) — the unified background-task ledger (P2-TASKLEDGER, ULTRAPLAN
 * section 7.2). One row per engine-initiated task; the TEXT primary key is a UUID string supplied by the
 * {@link ai.forvum.engine.persistence.TaskRecorder}. {@code taskType}/{@code status} hold the enum
 * {@code dbValue}s ({@code cron|sub_agent|background}, {@code pending|running|completed|error}).
 */
@Entity
@Table(name = "tasks")
public class TaskEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "task_type", nullable = false)
    public String taskType;

    @Column(name = "cron_id")
    public String cronId;

    @Column(name = "sub_agent_id")
    public String subAgentId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "scheduled_for")
    public Long scheduledFor;

    @Column(name = "started_at")
    public Long startedAt;

    @Column(name = "completed_at")
    public Long completedAt;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "result")
    public String result;

    @Column(name = "error")
    public String error;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
