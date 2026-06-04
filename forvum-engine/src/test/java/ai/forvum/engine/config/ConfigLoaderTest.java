package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for {@link ConfigLoader} I/O primitives over a {@code @TempDir}. Uses a plain
 * {@link ObjectMapper} — no Quarkus boot needed for raw file reads.
 */
class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader(new ObjectMapper());

    @Test
    void readJsonReturnsParsedTree(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.json");
        Files.writeString(file, "{\"name\":\"forvum\",\"n\":3}");

        JsonNode node = loader.readJson(file).orElseThrow();
        assertEquals("forvum", node.get("name").asText());
        assertEquals(3, node.get("n").asInt());
    }

    @Test
    void readJsonEmptyWhenAbsent(@TempDir Path dir) {
        assertTrue(loader.readJson(dir.resolve("missing.json")).isEmpty());
    }

    @Test
    void readTextReturnsContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.md");
        Files.writeString(file, "# Persona");

        assertEquals("# Persona", loader.readText(file).orElseThrow());
    }

    @Test
    void readTextEmptyWhenAbsent(@TempDir Path dir) {
        assertTrue(loader.readText(dir.resolve("missing.md")).isEmpty());
    }

    @Test
    void listIdsFiltersBySuffixSortedAndStripsExtension(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.json"), "{}");
        Files.writeString(dir.resolve("a.json"), "{}");
        Files.writeString(dir.resolve("note.md"), "x");

        assertEquals(List.of("a", "b"), loader.listIds(dir, ".json"));
    }

    @Test
    void listIdsEmptyWhenDirAbsent(@TempDir Path dir) {
        assertEquals(List.of(), loader.listIds(dir.resolve("nope"), ".json"));
    }
}
