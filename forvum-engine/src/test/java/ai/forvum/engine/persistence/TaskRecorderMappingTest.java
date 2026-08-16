package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.forvum.core.TaskRecord;
import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

/**
 * Unit contract for {@link TaskRecorder#toEntity} (#180): the pure {@link TaskRecord} -> {@code tasks}
 * row mapping must carry every field losslessly (ids, the enum {@code dbValue}s, nullable timings and
 * outcome columns). The {@code persist()} call itself is exercised by the booted ledger ITs
 * (CronSchedulerFireIT / AgentRegistrySpawnLedgerIT); the mapping — the substantive logic — is measured
 * here without a Quarkus boot (X3: coverage is the Surefire run).
 */
class TaskRecorderMappingTest {

    @Test
    void mapsACompletedCronTaskLosslessly() {
        TaskRecord task = new TaskRecord("task-1", new AgentId("main"), TaskType.CRON, "daily-report",
                null, "daily report", 1000L, 1010L, 1500L, TaskStatus.COMPLETED, "the reply", null,
                490L, 999L);

        TaskEntity entity = TaskRecorder.toEntity(task);

        assertEquals("task-1", entity.id);
        assertEquals("main", entity.agentId);
        assertEquals("cron", entity.taskType);
        assertEquals("daily-report", entity.cronId);
        assertNull(entity.subAgentId);
        assertEquals("daily report", entity.name);
        assertEquals(1000L, entity.scheduledFor);
        assertEquals(1010L, entity.startedAt);
        assertEquals(1500L, entity.completedAt);
        assertEquals("completed", entity.status);
        assertEquals("the reply", entity.result);
        assertNull(entity.error);
        assertEquals(490L, entity.durationMs);
        assertEquals(999L, entity.createdAt);
    }

    @Test
    void mapsAFailedSubAgentTaskWithNullTimings() {
        TaskRecord task = new TaskRecord("task-2", new AgentId("main"), TaskType.SUB_AGENT, null,
                "researcher", "spawned worker", null, null, null, TaskStatus.ERROR, null, "boom",
                null, 42L);

        TaskEntity entity = TaskRecorder.toEntity(task);

        assertEquals("task-2", entity.id);
        assertEquals("sub_agent", entity.taskType);
        assertNull(entity.cronId);
        assertEquals("researcher", entity.subAgentId);
        assertNull(entity.scheduledFor);
        assertNull(entity.startedAt);
        assertNull(entity.completedAt);
        assertEquals("error", entity.status);
        assertNull(entity.result);
        assertEquals("boom", entity.error);
        assertNull(entity.durationMs);
        assertEquals(42L, entity.createdAt);
    }
}
