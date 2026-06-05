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
 * M10 Verify (ULTRAPLAN section 7.1): a scripted single turn through {@link AgentRegistry} against the
 * real Anthropic provider.
 *
 * <p>The {@code main} agent is seeded with {@code primaryModel = "anthropic:claude-opus-4-6"}. The test
 * calls {@link Agent#respond} under the {@code CURRENT_AGENT} binding and asserts:
 * <ol>
 *   <li>the assistant reply is non-empty, and</li>
 *   <li>at least one {@code provider_calls} row exists with {@code provider == "anthropic"}.</li>
 * </ol>
 *
 * <p><strong>Requires a valid API key:</strong> set {@code QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY}
 * before running — that is the environment variable MP Config maps to
 * {@code quarkus.langchain4j.anthropic.api-key} (standard Quarkus env-var naming convention).
 *
 * <p>{@code @Tag("live")} — excluded from the default build via the {@code ${excludedGroups}} Surefire
 * user property (defaulted to {@code live} in {@code forvum-app/pom.xml}). To run manually:
 * <pre>{@code
 *   QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY=sk-... ./mvnw -pl forvum-app test -Dgroups=live -DexcludedGroups=
 * }</pre>
 *
 * <p>NOT {@code @Transactional}: {@link Agent#respond} owns the turn's transaction boundary (the M7/M8
 * persist-after-success design), and the provider_calls audit row must survive a failed turn — wrapping
 * the turn here would roll that row back.
 */
@QuarkusTest
@TestProfile(AnthropicScriptedTurnE2E.LiveHomeProfile.class)
@Tag("live")
class AnthropicScriptedTurnE2E {

    @Inject
    AgentRegistry registry;

    @Test
    void scriptedTurnThroughAgentRegistryAgainstRealAnthropic() throws Exception {
        AgentId main = new AgentId("main");
        Agent agent = registry.getOrCreate(main);
        String sessionId = "e2e-anthropic-scripted";

        String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> agent.respond(sessionId, "Say hello in one word."));

        assertFalse(reply.isBlank(), "Anthropic must return a non-empty assistant reply");

        long anthropicRows = ProviderCallEntity.count("provider = ?1", "anthropic");
        assertTrue(anthropicRows >= 1,
                "provider_calls must have at least one row with provider='anthropic', found: " + anthropicRows);
    }

    /**
     * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with the {@code main} agent
     * spec ({@code primaryModel = "anthropic:claude-opus-4-6"}) — the same seed format the M7 registry
     * tests use, so the real file-driven load path (M4 {@code AgentReader}) is exercised end-to-end.
     */
    public static class LiveHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-live-anthropic-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"anthropic:claude-opus-4-6\","
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
