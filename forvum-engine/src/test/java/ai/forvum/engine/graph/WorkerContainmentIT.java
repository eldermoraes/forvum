package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * #167 spawned-worker containment (acceptance #3: "interactive, cron, approval-resume, and spawned-worker
 * flows calculate the same effective scope set"). A worker is a single direct generation with NO tool loop
 * (M18, {@link DefaultWorkerRunner#runWorker}), so it never reaches the {@code ToolExecutor} — it has no
 * surface on which to exceed the agent's role cap. This pins that property: even when the worker's model
 * emits a tool call, no tool is dispatched and no {@code tool_invocations} row is written. (Role-cap
 * INHERITANCE onto the child persona is separately proven by
 * {@code AgentRegistryTest.spawnInheritsDR8FieldsVerbatimButNotTheCycle}.) A regression that added a tool
 * loop to a worker would flip this red. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(WorkerContainmentIT.SpawnerHomeProfile.class)
class WorkerContainmentIT {

    @Inject
    AgentRegistry registry;

    @Inject
    WorkerRunner workerRunner;

    @Test
    void aSpawnedWorkerExecutesNoToolsEvenWhenItsModelEmitsAToolCall() {
        AgentId parent = new AgentId("spawner");
        registry.getOrCreate(parent); // register the parent persona (scripted model, belt fs.write)
        AgentId child = new AgentId("spawner-worker");
        workerRunner.spawn(parent, child, List.of("fs.write"));

        workerRunner.runWorker(child, "please write a file", "sess-contain");

        assertEquals(0L, ToolInvocationEntity.count("agentId = ?1", "spawner-worker"),
                "a worker does one direct generation with no tool loop — it executes no tools, so it cannot "
              + "exceed any scope cap (the scripted model's fs.write call is never dispatched)");
    }

    /** Seeds {@code spawner}: the scripted model (emits an fs.write tool call), belt {@code fs.write}. */
    public static class SpawnerHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-worker-contain-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("spawner.md"), "You are a spawner agent.");
                Files.writeString(agents.resolve("spawner.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
