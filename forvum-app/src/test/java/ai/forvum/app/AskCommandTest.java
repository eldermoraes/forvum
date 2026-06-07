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
 * {@code forvum ask "<prompt>"} runs one non-interactive turn for the {@code main} agent and prints the
 * assistant reply to stdout (exit 0). It is the out-of-process turn entry the native real-provider smoke
 * needs: {@code @QuarkusMainIntegrationTest}/{@code @Launch} expose no stdin, so the TUI REPL is
 * unreachable from an integration test — a subcommand is the only driver.
 *
 * <p>Here {@code main} is pinned to the in-process {@code fake} provider (replies {@code "pong"}), so the
 * turn is deterministic with no live LLM. Unlike {@code --help}/{@code --version}/{@code init}, {@code ask}
 * is NOT a {@code CommandMode} one-shot — the turn needs Flyway/the DB
 * ({@code messages}/{@code provider_calls}), so it boots the full path.
 */
@QuarkusMainTest
@TestProfile(AskCommandTest.FakeBackedHomeProfile.class)
class AskCommandTest {

    @Test
    @Launch({"ask", "hello"})
    void askRunsATurnAndPrintsTheReplyToStdout(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "ask must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("pong"),
                () -> "ask must print the assistant reply (fake provider 'pong'); got: " + result.getOutput());
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so the turn is deterministic. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-ask-home");
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
