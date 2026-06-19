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
 * Native parity for {@code forvum qa suite} (P2-QA {@code [NATIVE]}): runs the produced native binary
 * OUT-OF-PROCESS and asserts the bundled QA scenario pack passes end-to-end against the deterministic,
 * network-free {@code echo} provider. This proves three native-only properties at once that the boot-only
 * smoke never reaches:
 * <ul>
 *   <li>the bundled {@code qa/scenarios.json} resource is REACHABLE in the image
 *       ({@code quarkus.native.resources.includes=qa/**} — a missing hint would make the loader throw and
 *       the suite fail);</li>
 *   <li>the {@code echo} provider {@code resolve()}s + {@code chat()}s natively (the QA records + the turn
 *       path native-compile and run);</li>
 *   <li>the full turn path (graph → provider → ledger) runs natively for each scenario.</li>
 * </ul>
 *
 * <p>Offline and deterministic — NO {@code @Tag("live")}, so it runs in the DEFAULT native leg (unlike the
 * {@code @Tag("live")} {@code OllamaNativeTurnIT}). {@code qa} is NOT a {@code CommandMode} one-shot, so the
 * single launch boots the full Flyway/Panache path, migrates the seeded home's SQLite, and runs the suite —
 * a free real native exercise of the DB-backed turn path with no live LLM. The seeded home reaches the
 * binary as {@code -Dforvum.home} via the profile's config overrides (the IT launcher applies them), and
 * logs route to stderr so {@code getOutput()} carries only the suite summary.
 */
@QuarkusMainIntegrationTest
@TestProfile(QaSuiteNativeIT.EchoHomeProfile.class)
class QaSuiteNativeIT {

    @Test
    void nativeQaSuitePassesAgainstTheBundledPack(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("qa", "suite");
        assertEquals(0, result.exitCode(),
                () -> "native `forvum qa suite` must exit 0 (every bundled scenario passes against echo); "
                        + "stderr: " + result.getErrorOutput() + "; stdout: " + result.getOutput());
        assertTrue(result.getOutput().contains("passed."),
                () -> "native `forvum qa suite` must print the suite summary to stdout; got: "
                        + result.getOutput());
    }

    @Test
    void nativeQaWithAnUnknownChannelFailsByDefault(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("qa", "telegram");
        assertEquals(1, result.exitCode(),
                () -> "native `forvum qa telegram` (no matching scenario) must fail by default; stderr: "
                        + result.getErrorOutput());
    }

    /** Seeds {@code main} pinned to the bundled {@code echo} provider and routes logs to stderr. */
    public static class EchoHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-native-qa-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"echo:qa-model\", \"allowedTools\": [] }");
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
