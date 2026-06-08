package ai.forvum.engine.persistence;

import ai.forvum.core.TaskRecord;
import ai.forvum.sdk.TaskExecutor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * The engine implementation of the SDK {@link TaskExecutor} sink (P2-TASKLEDGER, ULTRAPLAN section 7.2):
 * maps a {@link TaskRecord} to a row in the {@code tasks} ledger. This is the single implementation —
 * plugins do NOT implement {@code TaskExecutor} (it is a sink SPI, not a sealed provider). Mirrors
 * {@link PanacheProviderCallRecorder}: a thin {@code @Transactional} persist of an immutable Layer-0
 * record into the V2 table.
 */
@ApplicationScoped
public class TaskRecorder implements TaskExecutor {

    @Override
    @Transactional
    public void record(TaskRecord task) {
        TaskEntity entity = new TaskEntity();
        entity.id = task.id();
        entity.agentId = task.agentId().value();
        entity.taskType = task.taskType().dbValue();
        entity.cronId = task.cronId();
        entity.subAgentId = task.subAgentId();
        entity.name = task.name();
        entity.scheduledFor = task.scheduledFor();
        entity.startedAt = task.startedAt();
        entity.completedAt = task.completedAt();
        entity.status = task.status().dbValue();
        entity.result = task.result();
        entity.error = task.error();
        entity.durationMs = task.durationMs();
        entity.createdAt = task.createdAt();
        entity.persist();
    }
}
