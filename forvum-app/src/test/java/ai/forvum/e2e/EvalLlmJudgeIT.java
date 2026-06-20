package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The opt-in LLM-judge eval path (P3-10 #58, Risk #10): runs {@code forvum eval} against the native binary
 * with BOTH the agent and the judge on a live Ollama. The suite asks the agent a question with an obvious
 * answer and a lenient floor, then scores each reply with the {@code llm:ollama:qwen2.5:0.5b} judge — proving
 * the pluggable LLM judge works end-to-end natively, the path the deterministic {@link ai.forvum.app.EvalNativeIT}
 * cannot exercise (it has no model).
 *
 * <p>{@code @Tag("live")} — off by default; Failsafe runs it only with {@code -DitGroups=live
 * -DitExcludedGroups=none} (the CI {@code native-turn} job, beside {@code OllamaNativeTurnIT}). Requires a
 * reachable Ollama with {@code qwen2.5:0.5b} pulled. The floor is {@code 0.0} so a cold 0.5B judge's
 * occasional miss does not red-bar the build — the gate proves the LLM-judge MACHINERY runs natively (suite
 * load → real turn → real judge call → aggregate → exit 0), not the tiny model's accuracy.
 */
@QuarkusMainIntegrationTest
@TestProfile(EvalLlmJudgeIT.LiveJudgeHomeProfile.class)
@Tag("live")
class EvalLlmJudgeIT {

    @Test
    void nativeEvalWithAnLlmJudgeRunsEndToEnd(QuarkusMainLauncher launcher) {
        // Retry budget 1 (the project's live-test discipline): a cold 0.5B model in a CI service container
        // can transiently time out. A genuine native-only regression (no HTTP client, a JSON-reflection gap)
        // fails BOTH attempts, so the gate still catches it.
        LaunchResult first = launcher.launch("eval", "live");
        LaunchResult result = first.exitCode() != 0 ? launcher.launch("eval", "live") : first;
        assertEquals(0, result.exitCode(),
                () -> "native `forvum eval` with an LLM judge must exit 0 (floor 0.0); stderr: "
                        + result.getErrorOutput() + "; stdout: " + result.getOutput());
    }

    /**
     * Seeds {@code main} pinned to {@code ollama:qwen2.5:0.5b} and a {@code live} suite whose judge is the
     * {@code llm:ollama:qwen2.5:0.5b} LLM judge, floor {@code 0.0}. Routes logs to stderr for a clean stdout.
     */
    public static class LiveJudgeHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-eval-live-judge-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen2.5:0.5b\", \"allowedTools\": [] }");
                Path eval = Files.createDirectories(home.resolve("eval"));
                Files.writeString(eval.resolve("live.json"), """
                        {
                          "agent": "main",
                          "floor": 0.0,
                          "judge": "llm:ollama:qwen2.5:0.5b",
                          "scenarios": [
                            { "id": "capital", "prompt": "What is the capital of France? Answer in one word.",
                              "expect": "the reply names Paris" }
                          ]
                        }
                        """);
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
