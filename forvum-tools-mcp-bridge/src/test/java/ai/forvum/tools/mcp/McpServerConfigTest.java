package ai.forvum.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link McpServerConfig} reads {@code $FORVUM_HOME/mcp-servers/<id>.json}: the per-field {@code parse}
 * defaults, the {@code readAll} directory scan (sorted, skips malformed/non-json, empty when absent), and
 * the {@code resolveHome} fallback. Plain POJO tests — no Quarkus boot.
 */
class McpServerConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTransportUrlEnabledAndHeaders() throws Exception {
        McpServerSpec spec = McpServerConfig.parse("weather", MAPPER.readTree(
                "{ \"transport\": \"sse\", \"url\": \"http://localhost:9000/sse\", \"enabled\": true, "
              + "\"headers\": { \"Authorization\": \"Bearer t\", \"X-Env\": \"prod\" } }"));

        assertEquals("weather", spec.id());
        assertTrue(spec.enabled());
        assertEquals("sse", spec.transport());
        assertEquals("http://localhost:9000/sse", spec.url());
        assertTrue(spec.isHttp());
        assertEquals("Bearer t", spec.headers().get("Authorization"));
        assertEquals("prod", spec.headers().get("X-Env"));
    }

    @Test
    void transportDefaultsToHttpWhenAbsent() throws Exception {
        McpServerSpec spec = McpServerConfig.parse("s", MAPPER.readTree("{ \"url\": \"http://x/sse\" }"));
        assertEquals("http", spec.transport());
        assertTrue(spec.isHttp());
    }

    @Test
    void enabledDefaultsTrueButIsHonoredWhenSetFalse() throws Exception {
        assertTrue(McpServerConfig.parse("s", MAPPER.readTree("{ \"url\": \"http://x/sse\" }")).enabled());
        assertFalse(McpServerConfig.parse("s",
                MAPPER.readTree("{ \"enabled\": false, \"url\": \"http://x/sse\" }")).enabled());
    }

    @Test
    void absentOrBlankUrlAndHeadersDefaultCleanly() throws Exception {
        McpServerSpec spec = McpServerConfig.parse("s", MAPPER.readTree("{ \"url\": \"  \" }"));
        assertEquals(null, spec.url(), "a blank url is treated as absent (null)");
        assertTrue(spec.headers().isEmpty(), "no headers node → empty map");
    }

    @Test
    void readAllReturnsEmptyWhenDirectoryAbsent(@TempDir Path home) {
        McpServerConfig config = new McpServerConfig(home.resolve("mcp-servers"));
        assertTrue(config.readAll().isEmpty(), "no mcp-servers/ dir → empty registry (inert with no ~/.forvum/)");
    }

    @Test
    void readAllReadsEveryJsonSortedById(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("weather.json"), "{ \"url\": \"http://w/sse\" }");
        Files.writeString(dir.resolve("calendar.json"), "{ \"transport\": \"sse\", \"url\": \"http://c/sse\" }");

        List<McpServerSpec> specs = new McpServerConfig(dir).readAll();

        assertEquals(2, specs.size());
        assertEquals("calendar", specs.get(0).id(), "sorted by filename → id");
        assertEquals("weather", specs.get(1).id());
    }

    @Test
    void readAllSkipsMalformedAndNonJsonFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("good.json"), "{ \"url\": \"http://g/sse\" }");
        Files.writeString(dir.resolve("broken.json"), "{ not json");
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        List<McpServerSpec> specs = new McpServerConfig(dir).readAll();

        assertEquals(1, specs.size(), "a malformed json is skipped, a non-json file ignored — never fatal");
        assertEquals("good", specs.get(0).id());
    }

    @Test
    void resolveHomeUsesConfiguredValueOrFallsBackToUserHomeDotForvum() {
        Path configured = McpServerConfig.resolveHome(Optional.of("/tmp/fv-home"), "/home/u");
        assertEquals(Path.of("/tmp/fv-home").toAbsolutePath().normalize(), configured);

        Path blank = McpServerConfig.resolveHome(Optional.of("  "), "/home/u");
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(), blank,
                "a blank forvum.home falls back to <user.home>/.forvum");

        Path absent = McpServerConfig.resolveHome(Optional.empty(), "/home/u");
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(), absent);
    }
}
