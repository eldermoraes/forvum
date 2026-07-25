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
 * {@code forvum tools} when {@code web.search} is belted but config-shaped-unconfigured (#184): a
 * {@code tools/web.json} that selects Brave with NO key. The tool then shows a config gap naming the exact
 * field to fix (never a value), and the command still exits 0. Post-#192 a keyless invoke would dial the
 * internet, so this seeds {@code {"backend":"brave"}} — the gap is computed offline.
 */
@QuarkusMainTest
@TestProfile(ToolsCommandBraveGapTest.HomeProfile.class)
class ToolsCommandBraveGapTest {

    @Test
    @Launch("tools")
    void beltedWebSearchWithNoKeyShowsAConfigGap(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "tools must exit 0; stderr: " + result.getErrorOutput());
        String out = result.getOutput();
        Assertions.assertTrue(out.contains("web.search") && out.contains("needs-config"),
                () -> "web.search must show a config gap; got:\n" + out);
        Assertions.assertTrue(out.contains("braveApiKey"),
                () -> "the gap must name braveApiKey; got:\n" + out);
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-tools-bravegap-home");
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
