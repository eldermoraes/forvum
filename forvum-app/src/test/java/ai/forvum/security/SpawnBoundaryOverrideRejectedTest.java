package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;

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
 * Security-test layer (ULTRAPLAN §10): the M17 spawn-boundary check (X5 / TEST-SEC). A sub-agent cannot
 * escalate across the spawn boundary — the {@link AgentRegistry#spawn} API takes only
 * {@code (parentId, childId, allowedTools)} and the child's tool belt MUST be a subset of the parent's,
 * so a spawned agent can neither widen its capabilities nor "become" a different user/identity (there is
 * no identity-override parameter on the spawn API; §5.4: "there is no API to override identity across the
 * spawn boundary"). This is exercised here through the assembled app's real engine registry against a
 * file-seeded {@code main} agent, mirroring the Telegram channel's threat model: an inbound message can
 * only ever spawn within the parent's authority.
 *
 * <p>Companion to {@code PermissionScopeMismatchTest} (M13 capability-scope denial) and
 * {@code PathTraversalDeniedTest} (M14 path confinement). Non-live, so it runs in the default build.
 */
@QuarkusTest
@TestProfile(SpawnBoundaryOverrideRejectedTest.SpawnHomeProfile.class)
class SpawnBoundaryOverrideRejectedTest {

    @Inject
    AgentRegistry registry;

    @Test
    void aSpawnedSubAgentCannotWidenItsToolBeltBeyondTheParent() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        // The parent's belt is {fs.read}; a child requesting fs.write is escalation across the spawn
        // boundary and must be rejected (a sub-agent never gains a capability the parent lacks).
        assertThrows(IllegalStateException.class,
                () -> registry.spawn(main, new AgentId("escalator"), List.of("fs.write")),
                "a sub-agent must not gain a tool the parent lacks");
    }

    @Test
    void aSpawnedSubAgentInheritsTheParentIdentityWithNoOverridePath() {
        AgentId main = new AgentId("main");
        registry.getOrCreate(main);

        // The spawn API exposes no identity-override parameter; the child is registered with the parent
        // as its parentId, inheriting the parent's system prompt + model — it does not "become" another
        // user. A narrower (subset) belt is the only thing a spawn may change.
        AgentId child = registry.spawn(main, new AgentId("worker"), List.of("fs.read"));
        Persona childPersona = registry.persona(child);

        assertEquals(main, childPersona.parent(),
                "a spawned sub-agent inherits its parent's identity; there is no spawn-time override");
        assertEquals(registry.persona(main).systemPrompt(), childPersona.systemPrompt(),
                "the child inherits the parent's persona, not an attacker-chosen one");
    }

    /** Seeds a {@code main} agent whose tool belt is {@code [fs.read]} into a throwaway temp home. */
    public static class SpawnHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-spawn-security-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [\"fs.read\"] }");
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
