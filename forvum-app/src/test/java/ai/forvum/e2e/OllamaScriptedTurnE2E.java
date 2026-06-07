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
 * <p>{@code @Tag("live")} — excluded from the default build via the {@code ${excludedGroups}} Surefire
 * user property (defaulted to {@code live} in {@code forvum-app/pom.xml}). To run manually, clear the
 * exclusion AND select the group:
 * <pre>{@code
 *   ./mvnw -pl forvum-app test -Dgroups=live -DexcludedGroups=
 * }</pre>
 *
 * <p><strong>Native (Risk #5) — now covered by {@code OllamaNativeTurnIT}.</strong> The real-provider
 * native scripted-turn smoke ULTRAPLAN §8 Risk #5 mandates runs out-of-process against the built binary
 * via the {@code forvum ask} command ({@code @QuarkusMainIntegrationTest @Tag("live")}), gated in CI by
 * the linux-only {@code native-turn} job (an {@code ollama/ollama} service running {@code qwen2.5:0.5b}).
 * That IT asserts stdout only (exit 0 + a non-blank reply, since it runs out-of-process); this JVM
 * {@code @Tag("live")} test keeps the in-process ledger assertions ({@code provider_calls}).
 */
@QuarkusTest
@TestProfile(OllamaScriptedTurnE2E.LiveHomeProfile.class)
@Tag("live")
class OllamaScriptedTurnE2E {

    @Inject
    AgentRegistry registry;

    @Test
    void scriptedTurnThroughAgentRegistryAgainstRealOllama() throws Exception {
        // Deliberately NOT @Transactional: Agent.respond() owns the turn's transaction boundary (the
        // M7/M8 persist-after-success design), and the provider_calls audit row must survive a failed
        // turn — wrapping the turn here would roll that row back. Panache count() reads fine outside a
        // tx in @QuarkusTest (mirrors AgentTurnTest's failure-path method).
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
