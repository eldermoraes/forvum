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
 * {@code forvum doctor} over a home that has only an ADVISORY problem (an initialised home with no agents):
 * it must print the WARNING and the {@code N problem(s)} summary, yet still exit 0 — warnings are advisory
 * and must not fail the exit. This pins the command-boundary contract that the engine tests (which assert on
 * {@code DoctorReport.healthy()} directly) cannot reach: that {@code DoctorCommand} maps a warning-only,
 * non-empty report to exit 0 (not the {@code findings.isEmpty() ? 0 : 1} mistake).
 */
@QuarkusMainTest
@TestProfile(DoctorCommandWarningTest.WarningOnlyHomeProfile.class)
class DoctorCommandWarningTest {

    @Test
    @Launch({"doctor"})
    void doctorWithOnlyWarningsExitsZeroButReportsThem(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "a warning-only home must still exit 0; stderr: " + result.getErrorOutput()
                    + "; stdout: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("WARNING"),
                () -> "doctor must print the warning; got: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains("warning(s)"),
                () -> "doctor must summarise the warning count; got: " + result.getOutput());
    }

    /** An initialised home (so it is not the absent-home error) that declares no agents → one WARNING. */
    public static class WarningOnlyHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-warn-home");
                Files.createDirectories(home.resolve("agents")); // present but empty → "no agents" warning
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
