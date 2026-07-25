package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link MultimodalToolConfig}: absent → defaults; override parsed; malformed → exception naming the path;
 *  caps honored. */
class MultimodalToolConfigTest {

    @TempDir
    Path tmp;

    @Test
    void absentFileYieldsDefaults() {
        MultimodalToolConfig config = new MultimodalToolConfig(tmp.resolve("tools").resolve("multimodal.json"));
        MultimodalToolConfig.Spec spec = config.read();
        assertTrue(spec.model().isEmpty(), "no override => the agent's primary model");
        assertEquals(null, spec.modelOrNull());
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_FILE_BYTES, spec.maxFileBytes());
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_PDF_TEXT_CHARS, spec.maxPdfTextChars());
    }

    @Test
    void overrideAndCapsAreParsed() throws IOException {
        Path file = write("{ \"model\": \"ollama:llava\", \"maxFileBytes\": 1024, \"maxPdfTextChars\": 200 }");
        MultimodalToolConfig.Spec spec = new MultimodalToolConfig(file).read();
        assertEquals("ollama:llava", spec.modelOrNull());
        assertEquals(1024L, spec.maxFileBytes());
        assertEquals(200, spec.maxPdfTextChars());
    }

    @Test
    void nonPositiveCapsFallBackToDefaults() throws IOException {
        Path file = write("{ \"maxFileBytes\": 0, \"maxPdfTextChars\": -5 }");
        MultimodalToolConfig.Spec spec = new MultimodalToolConfig(file).read();
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_FILE_BYTES, spec.maxFileBytes());
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_PDF_TEXT_CHARS, spec.maxPdfTextChars());
        assertTrue(spec.model().isEmpty());
    }

    @Test
    void blankModelIsTreatedAsAbsent() throws IOException {
        Path file = write("{ \"model\": \"   \" }");
        assertTrue(new MultimodalToolConfig(file).read().model().isEmpty());
    }

    @Test
    void malformedFileThrowsNamingThePathNotTheContent() throws IOException {
        Path file = write("{ not json ");
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> new MultimodalToolConfig(file).read());
        assertTrue(e.getMessage().contains(file.toString()), "the error names the file path");
        assertFalse(e.getMessage().contains("not json"), "the error never echoes file content");
    }

    @Test
    void nonObjectRootAndNonNumberCapsYieldDefaults() throws IOException {
        assertTrue(new MultimodalToolConfig(write("[1,2,3]")).read().model().isEmpty(),
                "a non-object root parses to defaults");
        MultimodalToolConfig.Spec spec = new MultimodalToolConfig(
                write("{ \"maxFileBytes\": \"big\", \"maxPdfTextChars\": \"lots\" }")).read();
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_FILE_BYTES, spec.maxFileBytes());
        assertEquals(MultimodalToolConfig.DEFAULT_MAX_PDF_TEXT_CHARS, spec.maxPdfTextChars());
    }

    private Path write(String json) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("tools"));
        Path file = dir.resolve("multimodal.json");
        Files.writeString(file, json);
        return file;
    }
}
