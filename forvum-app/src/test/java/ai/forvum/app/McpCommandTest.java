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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * {@code forvum mcp add} / {@code forvum mcp list} end-to-end through the CLI (P2-13). {@code add} writes
 * {@code ~/.forvum/mcp-servers/<id>.json} owner-only ({@code 0600}); {@code list} reads the registry back.
 * Hermetic: {@code forvum.home} is an isolated temp dir; the registered URL is a loopback port that refuses
 * fast, so the boot-time MCP connect is best-effort and {@code list} reports no surfaced tools (never hangs
 * on DNS). Runs under Surefire (the forvum-app {@code @QuarkusMainTest} family, CLAUDE.md §4).
 */
@QuarkusMainTest
@TestProfile(McpCommandTest.HomeProfile.class)
class McpCommandTest {

    /** A loopback URL whose port refuses immediately — a fast, deterministic "unreachable" MCP server. */
    private static final String URL = "http://127.0.0.1:1/sse";

    @Test
    void addWritesAnOwnerOnlyServerFileWithExplicitId(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("mcp", "add", URL, "--id", "weather");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "mcp add must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("Registered MCP server 'weather'"),
                () -> "must echo the registered server id; got: " + result.getOutput());

        Path file = HomeProfile.FORVUM_HOME.resolve("mcp-servers").resolve("weather.json");
        Assertions.assertTrue(Files.isRegularFile(file), "weather.json must be written");
        String json = Files.readString(file);
        Assertions.assertTrue(json.contains("\"transport\" : \"http\""), () -> "got: " + json);
        Assertions.assertTrue(json.contains("\"url\" : \"" + URL + "\""), () -> "got: " + json);
        Assertions.assertTrue(json.contains("\"enabled\" : true"), () -> "got: " + json);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Assertions.assertEquals("rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "the server file may hold credentials — it must be owner-only (0600)");
        }
    }

    @Test
    void addDerivesTheIdFromTheUrlHostWhenOmitted(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "add", URL);

        Assertions.assertEquals(0, result.exitCode(),
                () -> "mcp add with no --id must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(
                Files.isRegularFile(HomeProfile.FORVUM_HOME.resolve("mcp-servers").resolve("127.0.0.1.json")),
                "the id defaults to the URL host (127.0.0.1)");
    }

    @Test
    void addParsesRepeatedHeaders(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("mcp", "add", URL, "--id", "authed",
                "-H", "Authorization: Bearer t", "-H", "X-Env: prod");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "mcp add with headers must exit 0; stderr: " + result.getErrorOutput());
        String json = Files.readString(HomeProfile.FORVUM_HOME.resolve("mcp-servers").resolve("authed.json"));
        Assertions.assertTrue(json.contains("\"Authorization\" : \"Bearer t\""), () -> "got: " + json);
        Assertions.assertTrue(json.contains("\"X-Env\" : \"prod\""), () -> "got: " + json);
    }

    @Test
    void listShowsARegisteredServer(QuarkusMainLauncher launcher) {
        Assertions.assertEquals(0, launcher.launch("mcp", "add", URL, "--id", "listme").exitCode());

        LaunchResult result = launcher.launch("mcp", "list");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "mcp list must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("listme"),
                () -> "list must show the registered server id; got: " + result.getOutput());
        Assertions.assertTrue(result.getOutput().contains(URL),
                () -> "list must show the server URL; got: " + result.getOutput());
    }

    @Test
    void listLabelsDisabledAndUnsupportedTransportServers(QuarkusMainLauncher launcher) throws IOException {
        Path dir = Files.createDirectories(HomeProfile.FORVUM_HOME.resolve("mcp-servers"));
        Files.writeString(dir.resolve("offsrv.json"),
                "{ \"transport\": \"http\", \"url\": \"" + URL + "\", \"enabled\": false }");
        Files.writeString(dir.resolve("stdiosrv.json"),
                "{ \"transport\": \"stdio\", \"url\": \"" + URL + "\", \"enabled\": true }");

        LaunchResult result = launcher.launch("mcp", "list");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "mcp list must exit 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("offsrv") && result.getOutput().contains("disabled"),
                () -> "a disabled server is labelled disabled; got: " + result.getOutput());
        Assertions.assertTrue(
                result.getOutput().contains("stdiosrv") && result.getOutput().contains("UNSUPPORTED"),
                () -> "a stdio server is labelled UNSUPPORTED transport; got: " + result.getOutput());
    }

    @Test
    void bareMcpPrintsUsageAndExitsZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp");

        Assertions.assertEquals(0, result.exitCode(),
                () -> "bare `mcp` prints usage and exits 0; stderr: " + result.getErrorOutput());
        Assertions.assertTrue(result.getOutput().contains("add") && result.getOutput().contains("list"),
                () -> "usage lists the add/list subcommands; got: " + result.getOutput());
    }

    @Test
    void addRejectsAMalformedHeader(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "add", URL, "--id", "badhdr", "-H", "no-colon-here");

        Assertions.assertEquals(1, result.exitCode(), "a header without a colon is rejected");
        Assertions.assertTrue(result.getErrorOutput().contains("must be formatted"),
                () -> "the error names the required header format; got: " + result.getErrorOutput());
        Assertions.assertFalse(
                Files.exists(HomeProfile.FORVUM_HOME.resolve("mcp-servers").resolve("badhdr.json")),
                "a rejected add writes no file");
    }

    @Test
    void addFailsWhenNoServerIdCanBeDerived(QuarkusMainLauncher launcher) {
        // A hostless URL and no --id leaves nothing to derive an id from.
        LaunchResult result = launcher.launch("mcp", "add", "http:///sse");

        Assertions.assertEquals(1, result.exitCode(), "a hostless URL with no --id cannot derive an id");
        Assertions.assertTrue(result.getErrorOutput().contains("could not derive a server id"),
                () -> "the error explains the missing id; got: " + result.getErrorOutput());
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path FORVUM_HOME = createTempDir("forvum-mcp-cmd-home");

        @Override
        public Map<String, String> getConfigOverrides() {
            // `mcp` is a one-shot command; production's ForvumApplication.main leaves the bundled Web
            // channel's vertx-http listener unbound for one-shots (quarkus.http.host-enabled=false). The
            // @QuarkusMainTest launcher drives QuarkusApplication.run() directly (not that static main), so
            // replicate the unbind here — both to mirror production and so the four sequential launches in
            // this class do not contend for the HTTP port (the second+ launch would otherwise fail to bind).
            return Map.of(
                    "forvum.home", FORVUM_HOME.toString(),
                    "quarkus.http.host-enabled", "false",
                    // The registered URL refuses, and the engine ToolRegistry connects at boot to surface
                    // tools; cap that per-server wait at 1s so the sequential launches stay fast.
                    "forvum.mcp.connect-timeout-seconds", "1");
        }

        private static Path createTempDir(String prefix) {
            try {
                return Files.createTempDirectory(prefix);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
