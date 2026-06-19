package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code forvum eval <suite>} as a CAPR quality gate (P3-10 #58): the deterministic, offline matcher judge
 * scores each scenario's reply with NO live model. {@code main} is pinned to the in-process {@code fake}
 * provider (always replies {@code "pong"}), so the gate is fully deterministic:
 *
 * <ul>
 *   <li>a suite whose every scenario expects {@code pong} meets its floor → exit 0;</li>
 *   <li>a suite whose scenarios expect {@code ping} (which {@code pong} never matches) falls below its
 *       floor → exit 1 (the regression the gate must catch).</li>
 * </ul>
 *
 * <p>This is the red-checkable proof of the gate in BOTH directions; {@link EvalNativeIT} re-runs the
 * pass case against the native binary. {@code eval} is NOT a {@code CommandMode} one-shot — each scenario
 * is a real turn, so it boots the full Flyway/DB path.
 */
@QuarkusMainTest
@TestProfile(EvalCommandTest.FakeBackedEvalHomeProfile.class)
class EvalCommandTest {

    @Test
    @Launch({"eval", "pass"})
    void aPassingSuiteMeetsTheFloorAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "a suite at/above its floor must exit 0; stderr: " + result.getErrorOutput()
                    + "; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("OK: pass-rate meets the floor"),
                () -> "expected the OK line; got: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("1.00"),
                () -> "expected a 1.00 pass-rate; got: " + result.getOutput());
    }

    @Test
    @Launch(value = {"eval", "regress"}, exitCode = 1)
    void aSuiteBelowItsFloorExitsNonZero(LaunchResult result) {
        Assertions.assertEquals(1, result.exitCode(),
                () -> "a suite below its floor must exit non-zero; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("REGRESSION"),
                () -> "expected the REGRESSION line; got: " + result.getOutput());
    }

    @Test
    @Launch(value = {"eval", "missing"}, exitCode = 1)
    void aMissingSuiteFailsWithExitOne(LaunchResult result) {
        Assertions.assertEquals(1, result.exitCode(),
                () -> "an absent suite must exit 1; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("eval failed"),
                () -> "expected an 'eval failed' message on stderr; got: " + result.getErrorOutput());
    }

    /**
     * Seeds {@code main} pinned to the in-process {@code fake} provider (replies {@code "pong"}) plus two
     * eval suites: {@code pass} (expects {@code pong}, floor 1.0 — all pass) and {@code regress} (expects
     * {@code ping}, floor 0.8 — all fail).
     */
    public static class FakeBackedEvalHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-eval-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");

                Path eval = Files.createDirectories(home.resolve("eval"));
                // Every scenario expects 'pong' (the fake reply) → pass-rate 1.0 >= floor 1.0.
                Files.writeString(eval.resolve("pass.json"), """
                        {
                          "agent": "main",
                          "floor": 1.0,
                          "scenarios": [
                            { "id": "s1", "prompt": "say something", "expect": "pong" },
                            { "id": "s2", "prompt": "say it again", "expect": "pong" }
                          ]
                        }
                        """);
                // Every scenario expects 'ping' (the fake never says it) → pass-rate 0.0 < floor 0.8.
                Files.writeString(eval.resolve("regress.json"), """
                        {
                          "agent": "main",
                          "floor": 0.8,
                          "scenarios": [
                            { "id": "s1", "prompt": "say something", "expect": "ping", "match": "exact" },
                            { "id": "s2", "prompt": "say it again", "expect": "ping", "match": "exact" }
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
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
