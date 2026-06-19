package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Native parity for {@code forvum eval} (P3-10 #58): exercises the produced binary OUT-OF-PROCESS so a
 * native-only regression in the eval command wiring (suite load/parse, {@code EvalRunner} injection, turn
 * dispatch, CAPR aggregation, the exit-code gate) surfaces here and nowhere else in the default native leg.
 *
 * <p>Deterministic and offline (no live LLM), so it carries NO {@code @Tag("live")} and runs in the DEFAULT
 * native leg — like {@code DoctorNativeIT}/{@code SessionReplayNativeIT}. There is no in-process fake
 * provider in the production native image, so the offline path is proven two ways with NO model:
 * <ul>
 *   <li>a {@code floor: 0.0} suite: every scenario's turn fails to reach the (absent) provider → each is a
 *       non-passing result, but a 0.0 floor is still met → exit 0. This drives the WHOLE eval path natively
 *       (parse → run turns → aggregate → gate) — the binary booted Flyway/Panache and ran turns;</li>
 *   <li>a missing suite → the suite-load failure path exits 1 with an {@code eval failed} message.</li>
 * </ul>
 * The live-judge path (a real Ollama judge) is {@link EvalLlmJudgeIT}, {@code @Tag("live")}.
 */
@QuarkusMainIntegrationTest
@TestProfile(EvalNativeIT.OfflineEvalHomeProfile.class)
class EvalNativeIT {

    @Test
    void aFloorZeroSuiteRunsTheWholePathNativelyAndExitsZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("eval", "floor-zero");
        assertEquals(0, result.exitCode(),
                () -> "a floor-0.0 suite must exit 0 even when turns fail to reach a provider; stderr: "
                        + result.getErrorOutput() + "; stdout: " + result.getOutput());
        assertTrue(result.getOutput().contains("CAPR pass-rate"),
                () -> "the native eval must print the CAPR summary; got: " + result.getOutput());
    }

    @Test
    void aMissingSuiteFailsWithExitOneNatively(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("eval", "does-not-exist");
        assertEquals(1, result.exitCode(),
                () -> "an absent suite must exit 1 natively; stdout: " + result.getOutput());
        assertTrue(result.getErrorOutput().contains("eval failed"),
                () -> "expected an 'eval failed' message on stderr; got: " + result.getErrorOutput());
    }

    /**
     * Seeds {@code main} pinned to a real provider ({@code ollama}, so the ref resolves but no live service is
     * contacted in the default native leg — each turn fails to connect) plus a {@code floor: 0.0} suite, and
     * routes logs to stderr so stdout carries only the eval summary.
     */
    public static class OfflineEvalHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-native-eval-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen2.5:0.5b\", \"allowedTools\": [] }");
                Path eval = Files.createDirectories(home.resolve("eval"));
                Files.writeString(eval.resolve("floor-zero.json"), """
                        {
                          "agent": "main",
                          "floor": 0.0,
                          "scenarios": [
                            { "id": "s1", "prompt": "say hello", "expect": "anything" }
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
