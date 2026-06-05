package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * M9 Verify (ULTRAPLAN section 7.1): a scripted single turn through {@link AgentRegistry} against the
 * real Ollama provider.
 *
 * <p>The {@code main} agent is seeded with {@code primaryModel = "ollama:qwen3:1.7b"}. The test calls
 * {@link Agent#respond} under the {@code CURRENT_AGENT} binding and asserts:
 * <ol>
 *   <li>the assistant reply is non-empty, and</li>
 *   <li>at least one {@code provider_calls} row exists with {@code provider == "ollama"}.</li>
 * </ol>
 *
 * <p><strong>Requires Ollama running locally:</strong> {@code ollama serve} with {@code qwen3:1.7b}
 * pulled ({@code ollama pull qwen3:1.7b}).
 *
 * <p>{@code @Tag("live")} — excluded from the default build by the {@code maven-surefire-plugin}
 * {@code excludedGroups} configuration in {@code forvum-app/pom.xml}. To run manually:
 * <pre>{@code
 *   ./mvnw -pl forvum-app test -Dgroups=live
 * }</pre>
 */
@QuarkusTest
@TestProfile(OllamaScriptedTurnE2E.LiveHomeProfile.class)
@Tag("live")
class OllamaScriptedTurnE2E {

    @Inject
    AgentRegistry registry;

    @Test
    @Transactional
    void scriptedTurnThroughAgentRegistryAgainstRealOllama() throws Exception {
        AgentId main = new AgentId("main");
        Agent agent = registry.getOrCreate(main);
        String sessionId = "e2e-ollama-scripted";

        String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.respond(sessionId, "Say hello in one word."));

        assertFalse(reply.isBlank(), "Ollama must return a non-empty assistant reply");

        long ollamaRows = ProviderCallEntity.count("provider = ?1", "ollama");
        assertTrue(ollamaRows >= 1,
                "provider_calls must have at least one row with provider='ollama', found: " + ollamaRows);
    }

    /**
     * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with the {@code main} agent
     * spec ({@code primaryModel = "ollama:qwen3:1.7b"}) — the same seed format the M7 registry tests
     * use, so the real file-driven load path (M4 {@code AgentReader}) is exercised end-to-end.
     */
    public static class LiveHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-live-ollama-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\","
                      + " \"allowedTools\": [] }");
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
