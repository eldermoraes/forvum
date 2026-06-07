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
 * {@code forvum doctor} over a {@code ~/.forvum} with a malformed agent spec: it must surface the broken
 * file (naming it, at ERROR severity) and exit non-zero so scripts and CI can gate on it. This proves the
 * report→exit-code wiring of the command boundary; the engine {@code ConfigDoctorTest} exhaustively covers
 * the individual rules.
 */
@QuarkusMainTest
@TestProfile(DoctorCommandProblemTest.BrokenHomeProfile.class)
class DoctorCommandProblemTest {

    @Test
    @Launch(value = {"doctor"}, exitCode = 1)
    void doctorOnABrokenHomeNamesTheFileAndExitsNonZero(LaunchResult result) {
        Assertions.assertTrue(result.getOutput().contains("agents/broken.json"),
                () -> "doctor must name the offending file; got: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("ERROR"),
                () -> "doctor must mark the problem as an error; got: " + result.getOutput());
    }

    /** Seeds a single malformed agent spec so the run has exactly one ERROR. */
    public static class BrokenHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-bad-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("broken.json"), "{ this is not valid json ");
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
