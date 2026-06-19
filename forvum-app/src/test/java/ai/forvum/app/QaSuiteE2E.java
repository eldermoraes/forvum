package ai.forvum.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * End-to-end for the QA scenario suite (P2-QA): the {@link QaCommand} loads a pack and runs each scenario
 * through the real turn path — the SDK {@code ChannelTurnDriver} (the engine's {@code TurnService} on the
 * app classpath) → the agent runtime → the bundled, network-free {@code echo} provider — and gates exit code
 * on every scenario passing.
 *
 * <ul>
 *   <li><strong>Passing pack → exit 0:</strong> the bundled {@code qa/scenarios.json} run against a home
 *       pinned to {@code echo:} passes every scenario.</li>
 *   <li><strong>Channel target with no scenarios → exit 1 (fails-by-default):</strong> {@code qa nope}
 *       matches nothing.</li>
 *   <li><strong>Failing scenario → exit 1 (red-check):</strong> an override pack with a wrong expectation
 *       must fail — proving the gate is not green-by-default. If this flipped to 0, the verdict is broken.</li>
 *   <li><strong>Missing override pack → exit 1:</strong> an absent {@code --pack} is a failure, not a pass.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(QaSuiteE2E.EchoBackedHomeProfile.class)
class QaSuiteE2E {

    @Inject
    QaCommand qa;

    @Test
    void bundledSuitePassesAndExitsZero() {
        Captured c = run("suite", null);
        assertEquals(0, c.exit, () -> "the bundled QA suite must pass against the echo provider; stdout: "
                + c.out + "; stderr: " + c.err);
        assertTrue(c.out.contains("PASS cli-exact-greeting"),
                () -> "expected a per-scenario PASS line; got: " + c.out);
        assertTrue(c.out.contains("passed."), () -> "expected a summary line; got: " + c.out);
    }

    @Test
    void aChannelTargetWithNoScenariosFailsByDefault() {
        Captured c = run("telegram", null);
        assertEquals(1, c.exit, "a channel target matching no scenario must fail by default (exit 1)");
        assertTrue(c.err.contains("no scenarios ran"), () -> "expected the fails-by-default message; got: "
                + c.err);
    }

    @Test
    void aFailingScenarioFailsTheSuite(@TempDir Path dir) throws IOException {
        // Red-check: a wrong expectation MUST drive exit 1. If the runner were green-by-default this stays 0.
        Path pack = dir.resolve("failing.json");
        Files.writeString(pack, "{ \"scenarios\": [ { \"id\": \"wrong\", \"channel\": \"cli\", "
                + "\"input\": \"hello\", "
                + "\"expect\": { \"match\": \"exact\", \"value\": \"NOT THE ECHO\" } } ] }");
        Captured c = run("suite", pack);
        assertEquals(1, c.exit, "a scenario whose expectation does not match must fail the suite");
        assertTrue(c.out.contains("FAIL wrong"), () -> "expected a FAIL line for the bad scenario; got: "
                + c.out);
    }

    @Test
    void aPassingOverridePackExitsZero(@TempDir Path dir) throws IOException {
        // The same pack with the CORRECT expectation passes — proving the red-check above is the expectation,
        // not the harness (the harness is identical; only the expected value changed).
        Path pack = dir.resolve("passing.json");
        Files.writeString(pack, "{ \"scenarios\": [ { \"id\": \"right\", \"channel\": \"cli\", "
                + "\"input\": \"hello\", "
                + "\"expect\": { \"match\": \"exact\", \"value\": \"echo: hello\" } } ] }");
        Captured c = run("suite", pack);
        assertEquals(0, c.exit, () -> "a correct override pack must pass; stdout: " + c.out + "; stderr: "
                + c.err);
    }

    @Test
    void aMissingOverridePackFailsByDefault(@TempDir Path dir) {
        Captured c = run("suite", dir.resolve("absent.json"));
        assertEquals(1, c.exit, "an absent --pack must fail (not vacuously pass)");
        assertTrue(c.err.contains("could not load the scenario pack"),
                () -> "expected a load-failure message; got: " + c.err);
    }

    /** Drive the command directly with the given target/pack, capturing its streams + exit code. */
    private Captured run(String target, Path pack) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        qa.target = target;
        qa.pack = pack;
        int exit = qa.run(new PrintStream(outBytes, true, UTF_8), new PrintStream(errBytes, true, UTF_8));
        return new Captured(exit, outBytes.toString(UTF_8), errBytes.toString(UTF_8));
    }

    private record Captured(int exit, String out, String err) {
    }

    /** Seeds {@code main} pinned to the bundled, network-free {@code echo} provider so the suite needs no LLM. */
    public static class EchoBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-qa-e2e-home");
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
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
