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
import java.util.stream.Collectors;

/**
 * {@code forvum doctor} end-to-end over a home that belts {@code web.search} but has it config-shaped
 * unconfigured (Brave selected with no key, #184 D6): the assembled app gathers the real tool inventory
 * from {@code Instance<ToolProvider>} (via {@link ToolInventoryCollector}) and {@code ConfigDoctor} emits a
 * WARNING for the belted-but-unconfigured tool. It is advisory, so doctor still exits 0. The
 * keyless-default (no gap) direction is covered by {@code DoctorCommandHealthyTest} + the engine unit test.
 */
@QuarkusMainTest
@TestProfile(DoctorBeltGapWarningTest.HomeProfile.class)
class DoctorBeltGapWarningTest {

    @Test
    @Launch("doctor")
    void aBeltedUnconfiguredToolIsWarnedAndDoctorStillExitsZero(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "a belt gap is advisory — doctor stays exit 0; stderr: " + result.getErrorOutput()
                    + "; stdout: " + result.getOutput());
        String out = result.getOutput();
        Assertions.assertTrue(out.contains("WARNING") && out.contains("web.search")
                        && out.contains("in the belt but not configured"),
                () -> "doctor must warn on the belted-unconfigured web.search; got:\n" + out);
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-doctor-beltgap-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                String belt = InitCommand.DEFAULT_ALLOWED_TOOLS.stream()
                        .map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\", \"identityId\": \"default\", "
                      + "\"allowedTools\": [" + belt + "] }");
                Files.createDirectories(home.resolve("identities"));
                Files.writeString(home.resolve("identities").resolve("default.json"),
                        "{ \"channelAccounts\": {} }");
                Path tools = Files.createDirectories(home.resolve("tools"));
                Files.writeString(tools.resolve("web.json"), "{ \"backend\": \"brave\" }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString(), "quarkus.http.host-enabled", "false");
        }
    }
}
