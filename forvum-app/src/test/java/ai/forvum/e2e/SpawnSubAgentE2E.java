package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.CaprEventEntity;

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
 * E2E scenario 5 (ULTRAPLAN §7.4 / X6): sub-agent spawn (M7 {@code AgentRegistry.spawn} + M18 turn). The
 * {@code main} agent spawns a distinct child sub-agent whose tool belt narrows the parent's, then a real
 * turn runs AS the child through the in-process fake model and is ledgered under the child's agent id.
 *
 * <p>Asserted end-to-end:
 * <ol>
 *   <li>{@code spawn} registers a DISTINCT child id (a child can never reuse the parent id, and never
 *       silently overwrite an existing agent — the M7 distinctness guard).</li>
 *   <li>the child's tool belt must be a SUBSET of the parent's: spawning with a tool the parent lacks is
 *       rejected (a child can never gain a capability the parent lacks — the "Isolate" pillar's boundary).</li>
 *   <li>a turn bound to the child ({@code CURRENT_AGENT = child}) produces a non-empty reply, and</li>
 *   <li>that turn writes a {@code capr_events} verdict row stamped with the CHILD's agent id — observable
 *       proof the sub-agent ran its own ledgered turn (OTel spans do not exist in v0.1, so the DB row is
 *       the observable side-effect, per X6's span-less guidance).</li>
 * </ol>
 *
 * <p>The seeded {@code main} has an empty tool belt, so the child's only valid subset is also empty —
 * which is sufficient: the spawn distinctness + subset guard and the per-child ledgering are what scenario
 * 5 verifies, not a specific tool. NOT {@code @Transactional}: {@code Agent.respond} owns the turn's
 * transaction boundary (the M7/M8 persist-after-success design).
 */
@QuarkusTest
@TestProfile(SpawnSubAgentE2E.FakeBackedHomeProfile.class)
class SpawnSubAgentE2E {

    @Inject
    AgentRegistry registry;

    @Test
    void aSpawnedSubAgentRunsAnIsolatedLedgeredTurn() throws Exception {
        AgentId parent = new AgentId("main");
        AgentId child = new AgentId("main-worker");
        registry.getOrCreate(parent); // load the parent spec so spawn can read its tool belt

        // (2) the child belt must be a subset of the parent's empty belt — a non-subset spawn is rejected.
        assertThrows(IllegalStateException.class,
                () -> registry.spawn(parent, child, List.of("fs.read")),
                "spawning with a tool the parent lacks must be refused");

        // (1) a valid spawn registers a distinct child id.
        AgentId spawned = registry.spawn(parent, child, List.of());
        assertEquals(child, spawned);
        assertNotEquals(parent, spawned, "the child id must differ from the parent");

        // (3) a turn AS the child produces a reply through the fake model.
        Agent agent = registry.getOrCreate(child);
        String sessionId = "e2e-spawn-child";
        String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, child)
                .call(() -> agent.respond(sessionId, "summarize this"));
        assertFalse(reply.isBlank(), "the spawned sub-agent must produce a non-empty reply");

        // (4) the turn is ledgered under the CHILD's agent id (the observable side-effect, span-less v0.1).
        long childCaprRows = CaprEventEntity.count("agentId = ?1", child.value());
        assertTrue(childCaprRows >= 1,
                "the sub-agent's turn must write a capr_events row stamped with the child agent id; found: "
                        + childCaprRows);
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider with an empty tool belt. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-spawn-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
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
