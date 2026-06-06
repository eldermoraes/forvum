package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.agent.AgentRegistryTestHomeProfile;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Exercises the production {@link DefaultWorkerRunner} end-to-end (the {@link SupervisorGraph} unit tests
 * inject a fake): {@link DefaultWorkerRunner#spawn} materializes a child of a registered parent via
 * {@code AgentRegistry.spawn} (rejecting a self-parent), and {@link DefaultWorkerRunner#runWorker} re-binds
 * the child's {@code @AgentScoped} context and drives a single generation through {@link
 * ai.forvum.engine.agent.FakeModelProvider}. Uses the M7 test-home profile so {@code faker} is a real,
 * model-resolvable parent.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class DefaultWorkerRunnerIT {

    @Inject
    AgentRegistry registry;

    @Inject
    WorkerRunner workerRunner;

    @Test
    void spawnRegistersTheChildThenRunWorkerDrivesItsTurn() {
        AgentId parent = new AgentId("faker");
        registry.getOrCreate(parent); // register the parent persona from the test home

        AgentId child = new AgentId("worker-recon");
        workerRunner.spawn(parent, child, List.of());

        assertNotNull(registry.persona(child), "spawn registered the child persona");
        assertEquals(parent, registry.persona(child).parent(), "the child is parented to the spawner");

        String digest = workerRunner.runWorker(child, "do the subtask", "sess-worker");
        assertFalse(digest.isBlank(), "runWorker drove the child's turn and returned a non-empty digest");
    }

    @Test
    void spawnRejectsAChildIdEqualToItsParent() {
        AgentId parent = new AgentId("faker");
        registry.getOrCreate(parent);

        assertThrows(IllegalStateException.class, () -> workerRunner.spawn(parent, parent, List.of()),
                "a worker cannot spawn itself");
    }
}
