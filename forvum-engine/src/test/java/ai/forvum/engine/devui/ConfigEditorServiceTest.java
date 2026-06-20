package ai.forvum.engine.devui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.devui.ConfigEditorService.SaveResult;
import ai.forvum.engine.doctor.Finding;
import ai.forvum.engine.doctor.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for the Dev UI live config editor service (P3-6 #54). No Quarkus boot — the service is
 * constructed directly over a synthetic {@code $FORVUM_HOME} ({@code @TempDir}) with a plain
 * {@link ConfigLoader} and a recording change-notifier (mirrors {@code ConfigDoctorTest}). It proves the
 * validate-then-save-then-rollback contract: a malformed edit surfaces findings and is NOT persisted, a
 * good edit writes and fires the hot-reload {@link ConfigurationChangedEvent}, and the editable surface is
 * confined (traversal/unknown-folder rejected).
 */
class ConfigEditorServiceTest {

    @TempDir
    Path home;

    private static final Set<String> KNOWN = Set.of("ollama");

    private final List<ConfigurationChangedEvent> fired = new ArrayList<>();

    private ConfigEditorService service() {
        ForvumHome forvumHome = new ForvumHome(Optional.of(home.toString()));
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        return new ConfigEditorService(forvumHome, loader, KNOWN, fired::add);
    }

    private void write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String relative) throws IOException {
        return Files.readString(home.resolve(relative));
    }

    private void seedValidMainAgent() throws IOException {
        write("agents/main.md", "You are the main agent.");
        write("agents/main.json", "{\"primaryModel\":\"ollama:qwen3:1.7b\",\"allowedTools\":[]}");
    }

    @Test
    void filesListsExistingEditableConfigsAsRelativePaths() throws IOException {
        seedValidMainAgent();
        write("crons/nightly.json",
                "{\"schedule\":\"0 0 * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:qwen3:1.7b\"}");
        write("config.json", "{}");

        List<String> files = service().files();

        assertTrue(files.contains("agents/main.json"), () -> files.toString());
        assertTrue(files.contains("agents/main.md"), () -> files.toString());
        assertTrue(files.contains("crons/nightly.json"), () -> files.toString());
        assertTrue(files.contains("config.json"), () -> files.toString());
        // Sorted, no duplicates.
        assertEquals(files.stream().distinct().sorted().toList(), files);
    }

    @Test
    void readReturnsCurrentContentAndEmptyForAbsentFile() throws IOException {
        seedValidMainAgent();

        assertEquals(Optional.of("{\"primaryModel\":\"ollama:qwen3:1.7b\",\"allowedTools\":[]}"),
                service().read("agents/main.json"));
        assertEquals(Optional.empty(), service().read("agents/absent.json"));
    }

    @Test
    void saveAGoodEditWritesAndFiresAHotReloadEvent() throws IOException {
        seedValidMainAgent();

        String edited = "{\"primaryModel\":\"ollama:qwen3:4b\",\"allowedTools\":[]}";
        SaveResult result = service().save("agents/main.json", edited);

        assertTrue(result.saved(), () -> "a valid edit must be saved; findings: " + result.findings());
        assertEquals(edited, read("agents/main.json"));
        assertEquals(1, fired.size(), () -> "exactly one hot-reload event; got " + fired);
        assertEquals(Path.of("agents/main.json"), fired.get(0).path());
        assertEquals(ChangeType.MODIFIED, fired.get(0).type());
    }

    @Test
    void saveAMalformedEditIsRejectedAndRollsBackTheFile() throws IOException {
        seedValidMainAgent();
        String original = read("agents/main.json");

        SaveResult result = service().save("agents/main.json", "{ this is not valid json");

        assertFalse(result.saved(), "a malformed edit must not be saved");
        assertTrue(result.findings().stream().anyMatch(f -> f.severity() == Severity.ERROR),
                () -> "a malformed edit must surface an ERROR finding; got " + result.findings());
        assertEquals(original, read("agents/main.json"), "the original file must be restored on a bad save");
        assertTrue(fired.isEmpty(), "no hot-reload event on a rejected save");
    }

    @Test
    void saveAModelRefForAnUninstalledProviderIsRejected() throws IOException {
        seedValidMainAgent();
        String original = read("agents/main.json");

        SaveResult result = service().save("agents/main.json",
                "{\"primaryModel\":\"nonsuch:model\",\"allowedTools\":[]}");

        assertFalse(result.saved(), "an unknown-provider model ref must not be saved");
        assertTrue(result.findings().stream().anyMatch(f -> f.severity() == Severity.ERROR),
                () -> "an unknown provider must surface an ERROR; got " + result.findings());
        assertEquals(original, read("agents/main.json"));
        assertTrue(fired.isEmpty());
    }

    @Test
    void saveANewFileThatFailsValidationDeletesItRatherThanLeavingTheBadFile() throws IOException {
        seedValidMainAgent();

        SaveResult result = service().save("crons/broken.json", "{ not json");

        assertFalse(result.saved());
        assertFalse(Files.exists(home.resolve("crons/broken.json")),
                "a new file that fails validation must be removed, not left on disk");
        assertTrue(fired.isEmpty());
    }

    @Test
    void validateIsADryRunThatNeverTouchesTheOnDiskConfig() throws IOException {
        seedValidMainAgent();
        String original = read("agents/main.json");

        List<Finding> findings = service().validate("agents/main.json", "{ not json");

        assertTrue(findings.stream().anyMatch(f -> f.severity() == Severity.ERROR),
                () -> "a malformed candidate must surface an ERROR; got " + findings);
        assertEquals(original, read("agents/main.json"), "validate must not modify the on-disk file");
        assertTrue(fired.isEmpty(), "validate never fires a change event");
    }

    @Test
    void editableSurfaceIsConfinedAgainstTraversalAndUnknownFolders() throws IOException {
        seedValidMainAgent();
        ConfigEditorService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.read("../escape.json"));
        assertThrows(IllegalArgumentException.class, () -> service.save("../escape.json", "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.read("state/forvum.sqlite"));
        assertThrows(IllegalArgumentException.class, () -> service.read("agents/main.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.read(""));
    }
}
