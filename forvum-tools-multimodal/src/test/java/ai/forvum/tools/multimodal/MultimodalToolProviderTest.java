package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link MultimodalToolProvider}: the constant tool shape (ids, scope, parseable schemas, no confirm), and
 *  name-dispatch including the unknown-tool error. Fields are wired directly (no Quarkus boot). */
class MultimodalToolProviderTest {

    @TempDir
    Path ws;

    private MultimodalToolProvider provider;
    private FakeMediaAnalysis fake;

    @BeforeEach
    void setUp() {
        provider = new MultimodalToolProvider();
        fake = new FakeMediaAnalysis();
        provider.mediaAnalysis = fake;
        provider.workspaceRoot = new WorkspaceRoot(ws);
        // A config bound to an absent file → defaults, no filesystem config required.
        provider.config = new MultimodalToolConfig(ws.resolve("tools").resolve("multimodal.json"));
    }

    @Test
    void extensionIdMatchesThePluginJson() {
        assertEquals("multimodal", provider.extensionId());
    }

    @Test
    void toolsShapeIsConstantAndWellFormed() {
        List<ToolSpec> tools = provider.tools();
        assertEquals(provider.tools(), tools, "tools() is a constant (zero boot IO, P2-13)");

        Set<String> names = tools.stream().map(ToolSpec::name).collect(Collectors.toSet());
        assertEquals(Set.of("image.analyze", "pdf.analyze"), names);

        ObjectMapper mapper = new ObjectMapper();
        for (ToolSpec spec : tools) {
            assertEquals(PermissionScope.MEDIA_ANALYZE, spec.requiredScope(), spec.name() + " gates on MEDIA_ANALYZE");
            assertFalse(spec.userConfirmRequired(), spec.name() + " is read-only + model-spend, no confirm gate");
            assertDoesNotThrow(() -> mapper.readTree(spec.parametersJsonSchema()),
                    spec.name() + " parameters schema is valid JSON");
        }
    }

    @Test
    void invokeDispatchesImageAnalyze() throws IOException {
        Files.write(ws.resolve("a.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2});
        String out = provider.invoke("image.analyze", Map.of("paths", List.of("a.png"), "prompt", "what is this"));
        assertEquals("analysis-result", out);
        assertEquals("what is this", fake.lastPrompt);
        assertEquals(1, fake.lastMedia.size());
    }

    @Test
    void invokeDispatchesPdfAnalyze() throws IOException {
        Files.write(ws.resolve("d.pdf"), PdfFixtures.textPdf("hello"));
        String out = provider.invoke("pdf.analyze", Map.of("path", "d.pdf"));
        assertEquals("analysis-result", out);
    }

    @Test
    void invokeRejectsAnUnknownTool() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("image.enhance", Map.of()));
        assertTrue(e.getMessage().contains("image.enhance"));
    }

    @Test
    void imageAnalyzeRequiresPaths() {
        assertThrows(IllegalArgumentException.class, () -> provider.invoke("image.analyze", Map.of()));
    }

    @Test
    void configGapsIsEmptyBecauseTheToolsNeedNoConfig() {
        assertTrue(provider.configGaps().isEmpty(),
                "vision capability is a runtime/model property, not a config gap (#184 delta)");
    }
}
