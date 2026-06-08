package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.context.CurrentAgent;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * E2E scenario 9 (ULTRAPLAN §7.4 / X6): hot reload without restart (M4 + M7). An edit to an
 * {@code agents/<id>.json} file under {@code $FORVUM_HOME}, surfaced as the M4 {@code WatchService}'s
 * {@code ConfigurationChangedEvent}, evicts the {@code AgentRegistry}'s cached spec so the NEXT turn
 * re-reads the agent from disk — no process restart.
 *
 * <p>Driven end-to-end in the assembled app: a real turn runs against the originally-pinned fake model,
 * then {@code main.json} is rewritten to a different model id and the config-changed event is fired (the
 * deterministic stand-in for the OS file watcher — the macOS poll latency makes a real watcher non-
 * deterministic, so the e2e fires the same CDI event the watcher would, the discipline used by the engine
 * {@code AgentRegistryTest} reload test). The next {@code persona()} reflects the NEW model, and a second
 * turn still converses through the reloaded spec — proving the agent reloaded live.
 *
 * <p>Both model ids resolve through the in-process {@code FakeModelProvider} (extension {@code fake}), so
 * the post-reload turn needs no LLM (the suite excludes inference, per the perf-gate convention) while the
 * persona's {@code ModelRef} change is the observable proof of the re-read. NOT {@code @Transactional}:
 * {@code Agent.respond} owns the turn's transaction boundary.
 */
@QuarkusTest
@TestProfile(HotReloadE2E.ReloadableHomeProfile.class)
class HotReloadE2E {

    @Inject
    AgentRegistry registry;

    @Inject
    Event<ConfigurationChangedEvent> configChanged;

    @Test
    void anAgentFileEditIsPickedUpOnTheNextTurnWithoutRestart() throws Exception {
        AgentId main = new AgentId("main");
        Agent agent = registry.getOrCreate(main);

        // First turn against the originally-pinned model.
        assertEquals(ModelRef.parse("fake:test-model"), registry.persona(main).primaryModel());
        String firstReply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.respond("e2e-hot-reload-1", "hello"));
        assertFalse(firstReply.isBlank(), "the pre-reload turn must converse");

        // Edit the agent file on disk, then surface the same event the WatchService would emit.
        Path json = ReloadableHomeProfile.HOME.resolve("agents").resolve("main.json");
        Files.writeString(json, "{ \"primaryModel\": \"fake:reloaded-model\", \"allowedTools\": [] }");
        configChanged.fire(new ConfigurationChangedEvent(Path.of("agents", "main.json"), ChangeType.MODIFIED));

        // The next access re-reads the changed file — no restart.
        registry.getOrCreate(main);
        assertEquals(ModelRef.parse("fake:reloaded-model"), registry.persona(main).primaryModel(),
                "a changed agent file must be re-read on the next turn (hot reload, no restart)");

        // And the reloaded agent still converses end-to-end (the new model id also resolves to fake).
        String secondReply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.respond("e2e-hot-reload-2", "hello again"));
        assertFalse(secondReply.isBlank(), "the reloaded agent must still converse");
    }

    /** Seeds {@code main} pinned to {@code fake:test-model}; the test rewrites it to {@code fake:reloaded-model}. */
    public static class ReloadableHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-hot-reload-e2e-home");
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
