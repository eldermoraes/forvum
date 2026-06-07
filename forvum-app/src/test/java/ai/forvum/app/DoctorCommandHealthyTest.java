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
 * {@code forvum doctor} over a fully valid {@code ~/.forvum}: it must print a clean bill of health and
 * exit 0. The seeded {@code main} agent is pinned to {@code ollama:} — a provider really on the app
 * classpath — so the model-ref cross-reference passes (no live Ollama is contacted; doctor only validates
 * the ref string). {@code doctor} is a {@code CommandMode} one-shot, so this boots off the DB/watcher path.
 *
 * <p>{@link DoctorNativeIT} re-runs this exact case against the native binary (the home is propagated to the
 * out-of-process runner via {@link HealthyHomeProfile#getConfigOverrides()} as {@code -Dforvum.home}).
 */
@QuarkusMainTest
@TestProfile(DoctorCommandHealthyTest.HealthyHomeProfile.class)
class DoctorCommandHealthyTest {

    @Test
    @Launch({"doctor"})
    void doctorOnAValidHomeReportsHealthyAndExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "doctor must exit 0 on a valid home; stderr: " + result.getErrorOutput()
                    + "; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("No problems found"),
                () -> "doctor must report a clean bill of health; got: " + result.getOutput());
    }

    /** Seeds a fully valid {@code main} agent (persona + spec pinned to a real provider). */
    public static class HealthyHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-ok-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"allowedTools\": [] }");
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
