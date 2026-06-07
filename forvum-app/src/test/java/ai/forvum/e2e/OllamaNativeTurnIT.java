package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Risk #5: the real-provider native scripted-turn smoke. Runs the produced native binary OUT-OF-PROCESS
 * via {@code forvum ask "<prompt>"} against a live Ollama, asserting the binary can actually converse —
 * the exact gap PR #111 fell through. Every prior native CI leg was a boot-only smoke
 * ({@code ForvumApplicationIT} = exit 0 + banner) that never reached {@code resolve()/chat()}, so a
 * native-only provider JSON/HTTP/reflection regression escaped silently.
 *
 * <p>Out-of-process (its own JVM + its own SQLite), so it asserts stdout ONLY — never Panache /
 * {@code provider_calls}. The in-process ledger assertions stay in {@code OllamaScriptedTurnE2E}
 * (JVM {@code @Tag("live")}).
 *
 * <ul>
 *   <li><strong>Exit 0 is the real gate:</strong> on any native turn failure ("No HTTP client found", a
 *       JSON-reflection gap, an unseeded home, model resolution) {@code TurnService} surfaces an
 *       {@code ErrorEvent} → {@code ask} exits 1 → this test fails. A green exit proves the full turn path
 *       (graph → provider → HTTP → chat) ran natively end-to-end.</li>
 *   <li><strong>Non-blank reply:</strong> the profile routes Quarkus logs to stderr
 *       ({@code quarkus.log.console.stderr=true}) so stdout carries only {@code ask}'s printed reply,
 *       making {@code getOutput()} a meaningful "the model actually answered" check.</li>
 * </ul>
 *
 * <p>{@code @Tag("live")} — off by default; Failsafe runs it only with
 * {@code -DitGroups=live -DitExcludedGroups=none} (the CI {@code native-turn} job; {@code none} is a
 * non-empty no-op tag — a blank {@code excludedGroups} makes JUnit discover zero tests). Requires a
 * reachable Ollama with {@code qwen2.5:0.5b} pulled (default base url {@code http://localhost:11434}).
 */
@QuarkusMainIntegrationTest
@TestProfile(OllamaNativeTurnIT.LiveHomeProfile.class)
@Tag("live")
class OllamaNativeTurnIT {

    @Test
    void nativeAskTurnAgainstRealOllamaPrintsAReply(QuarkusMainLauncher launcher) {
        // Retry budget 1 — matching the project's live-test discipline (ULTRAPLAN section 10 / CLAUDE.md
        // section 11): a real-LLM turn over a cold, freshly-pulled 0.5B model in a CI service container can
        // transiently cold-load, time out, or emit an empty completion. A single transient blip must not
        // red-bar an otherwise-good PR; a genuine native-only regression (no HTTP client, a JSON-reflection
        // gap) fails BOTH attempts (exit 1 / blank), so the gate still catches it.
        LaunchResult first = launchAsk(launcher);
        LaunchResult result = (first.exitCode() != 0 || first.getOutput().isBlank())
                ? launchAsk(launcher)
                : first;
        assertEquals(0, result.exitCode(),
                () -> "native `forvum ask` must exit 0 (a failed turn exits 1); stderr: "
                        + result.getErrorOutput());
        assertFalse(result.getOutput().isBlank(),
                () -> "native `forvum ask` must print a non-blank assistant reply to stdout; got: "
                        + result.getOutput());
    }

    private static LaunchResult launchAsk(QuarkusMainLauncher launcher) {
        return launcher.launch("ask", "Say hello in one word.");
    }

    /**
     * Seeds {@code main} pinned to {@code ollama:qwen2.5:0.5b} so the launched binary resolves a real model,
     * and routes Quarkus logs to stderr so stdout is the reply alone. The config overrides reach the
     * out-of-process binary as system properties (the integration-test launcher applies the test profile's
     * {@code getConfigOverrides()} to the launched artifact); if they did not, the home would be unseeded
     * and the turn would exit non-zero — so the exit-0 assertion also guards that propagation.
     */
    public static class LiveHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-native-ollama-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen2.5:0.5b\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.log.console.stderr", "true");
        }
    }
}
