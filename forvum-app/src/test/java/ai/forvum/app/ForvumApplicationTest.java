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
 * JVM command-mode smoke for {@link ForvumApplication}: launches the app in-process and asserts a
 * clean exit with the rendered banner. Reused against the built binary by {@code ForvumApplicationIT}.
 *
 * <p>$FORVUM_HOME is pinned to an empty temp dir so no ambient {@code channels/web.json} flips the app
 * into server mode (which would block on {@code waitForExit}); command mode with no server channel
 * exits 0 — the same contract the CI native smoke runs with (no {@code ~/.forvum/}).
 */
@QuarkusMainTest
@TestProfile(ForvumApplicationTest.EmptyHomeProfile.class)
class ForvumApplicationTest {

    @Test
    @Launch({})
    void runsAndPrintsBanner(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("Forvum"),
                () -> "Expected the Forvum banner in output, got: " + result.getOutput());
    }

    /** Pins an empty {@code $FORVUM_HOME} so the command-mode smoke is hermetic. */
    public static class EmptyHomeProfile implements QuarkusTestProfile {

        static final Path HOME = create();

        private static Path create() {
            try {
                return Files.createTempDirectory("forvum-cmdmode-home");
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
