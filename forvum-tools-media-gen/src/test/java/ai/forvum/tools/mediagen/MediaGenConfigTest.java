package ai.forvum.tools.mediagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Unit tests for {@link MediaGenConfig}: home resolution (the {@code ForvumHome} mirror), the on-demand
 * {@code tools/media-gen.json} read (absent → unconfigured, malformed → actionable failure), and the
 * hand-rolled JSON tree-walk parse (the M4 lesson: exercise the ABSENT state, not just the happy one).
 */
class MediaGenConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void homeResolutionPrefersConfiguredValueElseUserHome() {
        assertEquals(Path.of("/custom/home").toAbsolutePath().normalize(),
                MediaGenConfig.resolveHome(Optional.of("/custom/home"), "/ignored"));
        assertEquals(Path.of("/users/me", ".forvum").toAbsolutePath().normalize(),
                MediaGenConfig.resolveHome(Optional.empty(), "/users/me"));
        assertEquals(Path.of("/users/me", ".forvum").toAbsolutePath().normalize(),
                MediaGenConfig.resolveHome(Optional.of("  "), "/users/me"));
    }

    @Test
    void absentFileReadsAsUnconfigured(@TempDir Path dir) {
        MediaGenConfig config = new MediaGenConfig(dir.resolve("media-gen.json"));
        MediaGenConfig.Spec spec = config.read();
        assertFalse(spec.isReady());
        assertTrue(spec.baseUrl().isEmpty());
    }

    @Test
    void malformedFileFailsActionably(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("media-gen.json");
        Files.writeString(file, "{ not json");
        MediaGenConfig config = new MediaGenConfig(file);
        assertThrows(UncheckedIOException.class, config::read);
    }

    @Test
    void fullSpecParses() throws IOException {
        MediaGenConfig.Spec spec = MediaGenConfig.parse(MAPPER.readTree(
                "{\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"k-123\",\"model\":\"img-1\"}"));
        assertTrue(spec.isReady());
        assertEquals("https://api.example.com", spec.baseUrl().orElseThrow());
        assertEquals("k-123", spec.apiKey().orElseThrow());
        assertEquals("img-1", spec.model().orElseThrow());
    }

    @Test
    void blankAndMissingFieldsAreAbsent() throws IOException {
        MediaGenConfig.Spec spec = MediaGenConfig.parse(MAPPER.readTree(
                "{\"baseUrl\":\"https://api.example.com\",\"apiKey\":\"  \"}"));
        assertTrue(spec.isReady());
        assertTrue(spec.apiKey().isEmpty());
        assertTrue(spec.model().isEmpty());
    }

    @Test
    void nonObjectRootIsUnconfigured() throws IOException {
        assertFalse(MediaGenConfig.parse(MAPPER.readTree("[1,2]")).isReady());
        assertFalse(MediaGenConfig.parse(MAPPER.readTree("null")).isReady());
        assertFalse(MediaGenConfig.parse(null).isReady());
    }
}
