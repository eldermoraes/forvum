package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.TaskEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * P2-TASKLEDGER: a programmatic sub-agent {@link AgentRegistry#spawn} writes one queryable
 * {@code tasks} row — typed {@code sub_agent}, terminal {@code COMPLETED}, with {@code agent_id} = the
 * parent and {@code sub_agent_id} = the spawned child. Asserts on a child id this test alone creates, so
 * the shared static {@code AgentRegistryTestHomeProfile} home (one app instance across same-profile
 * tests) cannot pollute the count. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentRegistrySpawnLedgerIT {

    @Inject
    AgentRegistry registry;

    @Test
    void spawnRecordsASubAgentTasksRow() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        AgentId child = new AgentId("ledger-child");
        registry.spawn(main, child, List.of("fs.read"));

        assertEquals(1, TaskEntity.count("subAgentId = ?1", "ledger-child"),
                "one tasks-ledger row for the spawn");
        TaskEntity task = TaskEntity.find("subAgentId = ?1", "ledger-child").firstResult();
        assertEquals(TaskType.SUB_AGENT.dbValue(), task.taskType, "task_type is 'sub_agent'");
        assertEquals(TaskStatus.COMPLETED.dbValue(), task.status, "a successful spawn is 'completed'");
        assertEquals("main", task.agentId, "the task is attributed to the spawning parent");
    }
}
