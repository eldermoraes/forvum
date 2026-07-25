package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code forvum tools} over a scaffolded {@code ~/.forvum} (#184): lists every built-in tool with its scope,
 * confirm marker, owner, belt membership, and readiness — a config-read one-shot that NEVER connects. The
 * home mirrors the {@code init} scaffold (the widened default belt + {@code identityId}), plus a configured
 * Brave key (to prove no config VALUE is printed) and a loopback MCP server (to prove the bridge is listed
 * from the file, not connected — the P2-13 no-connect pin). Runs under Surefire (the {@code @QuarkusMainTest}
 * family, CLAUDE.md §4); {@code quarkus.http.host-enabled=false} mirrors production's one-shot unbind.
 */
@QuarkusMainTest
@TestProfile(ToolsCommandTest.HomeProfile.class)
class ToolsCommandTest {

    private static final String LEAK_TOKEN = "LEAK-TOKEN-XYZ";

    /** The output line for a given tool id (the row starts with two spaces + the id). */
    private static String rowFor(String output, String toolId) {
        return Arrays.stream(output.split("\\R"))
                .filter(line -> line.strip().startsWith(toolId + " ") || line.strip().equals(toolId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for '" + toolId + "' in:\n" + output));
    }

    @Test
    void listsEveryToolWithBeltAndReadiness(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("tools");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "tools must exit 0; stderr: " + result.getErrorOutput());
        String out = result.getOutput();

        // Every registered tool id is listed, including the piper-dependent tts.speak (not belted by default).
        for (String id : new String[] {
                "fs.read", "fs.write", "fs.list", "web.fetch", "web.search",
                "memory.save", "memory.recall", "shell.exec", "sandbox.run", "tts.speak"}) {
            Assertions.assertTrue(out.contains(id), () -> "expected tool '" + id + "' listed; got:\n" + out);
        }

        // web.search: WEB_SEARCH, in the default belt, ready (a configured Brave key).
        String webSearch = rowFor(out, "web.search");
        Assertions.assertTrue(webSearch.contains("WEB_SEARCH"), webSearch);
        Assertions.assertTrue(webSearch.contains("belt:yes"), () -> "web.search is belted by default: " + webSearch);
        Assertions.assertTrue(webSearch.contains("ready"), () -> "web.search is configured/ready: " + webSearch);

        // shell.exec: confirm-gated, NOT belted, and a config gap naming the file+field.
        String shell = rowFor(out, "shell.exec");
        Assertions.assertTrue(shell.contains("confirm"), () -> "shell.exec is confirm-gated: " + shell);
        Assertions.assertTrue(shell.contains("belt:no"), () -> "shell.exec is not belted by default: " + shell);
        Assertions.assertTrue(shell.contains("allowedCommands"), () -> "shell.exec names its config gap: " + shell);

        // tts.speak: NOT belted, gap naming piperBin.
        String tts = rowFor(out, "tts.speak");
        Assertions.assertTrue(tts.contains("belt:no"), () -> "tts.speak is not belted by default: " + tts);
        Assertions.assertTrue(tts.contains("piperBin"), () -> "tts.speak names its config gap: " + tts);
    }

    @Test
    void neverPrintsAConfigValue(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("tools");

        Assertions.assertFalse(result.getOutput().contains(LEAK_TOKEN),
                () -> "tools must never print a config VALUE (the Brave key); got:\n" + result.getOutput());
    }

    @Test
    void listsAConfiguredMcpServerFromTheFileWithoutConnecting(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("tools");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "tools must exit 0 even with an unreachable MCP server; stderr: " + result.getErrorOutput());
        String out = result.getOutput();
        Assertions.assertTrue(out.contains("mcp.weather.*"),
                () -> "a configured MCP server is listed from the file; got:\n" + out);
        Assertions.assertTrue(out.contains("forvum mcp list"),
                () -> "the MCP section points at 'forvum mcp list' to materialize; got:\n" + out);
        Assertions.assertTrue(out.contains("127.0.0.1"),
                () -> "the (non-secret) loopback host is shown; got:\n" + out);
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-tools-home");
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
                // A configured Brave key: web.search is ready, AND the value must never be printed.
                Files.writeString(tools.resolve("web.json"),
                        "{ \"backend\": \"brave\", \"braveApiKey\": \"" + LEAK_TOKEN + "\" }");
                Path mcp = Files.createDirectories(home.resolve("mcp-servers"));
                Files.writeString(mcp.resolve("weather.json"),
                        "{ \"transport\": \"http\", \"url\": \"http://127.0.0.1:1/sse\", \"enabled\": true }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.http.host-enabled", "false",
                    "forvum.mcp.connect-timeout-seconds", "1");
        }
    }
}
