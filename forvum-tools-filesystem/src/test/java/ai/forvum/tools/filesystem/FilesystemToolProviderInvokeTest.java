package ai.forvum.tools.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Execution contract for {@link FilesystemToolProvider#invoke(String, Map)} (M18 Option A): the provider
 * self-dispatches a tool call by name to the confined {@code Fs*Tool} logic, with no reflection. The
 * engine's {@code ToolExecutor} gates permission + audits the call; this test exercises the in-provider
 * dispatch and the IO round-trip directly against a {@code @TempDir} workspace.
 */
class FilesystemToolProviderInvokeTest {

    /** A {@code Map<String,Object>} (the engine parses model tool-call JSON to this) from key/value pairs. */
    private static Map<String, Object> args(String... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private FilesystemToolProvider providerFor(Path dir) {
        FilesystemToolProvider provider = new FilesystemToolProvider();
        provider.workspace = new WorkspaceRoot(dir);
        return provider;
    }

    @Test
    void invokeWriteThenReadRoundTrips(@TempDir Path dir) throws IOException {
        FilesystemToolProvider provider = providerFor(dir);

        String writeResult = provider.invoke("fs.write",
                args("path", "notes/today.md", "content", "buy milk"));

        assertTrue(writeResult.contains("today.md"), "fs.write returns a confirmation, got: " + writeResult);
        assertEquals("buy milk", Files.readString(dir.resolve("notes/today.md")),
                "the file is written under the workspace root");
        assertEquals("buy milk", provider.invoke("fs.read", args("path", "notes/today.md")),
                "fs.read returns the written content");
    }

    @Test
    void invokeListReturnsSortedEntriesAsLines(@TempDir Path dir) {
        FilesystemToolProvider provider = providerFor(dir);
        provider.invoke("fs.write", args("path", "beta.txt", "content", "b"));
        provider.invoke("fs.write", args("path", "alpha.txt", "content", "a"));

        assertEquals("alpha.txt\nbeta.txt", provider.invoke("fs.list", args("path", ".")),
                "fs.list returns the sorted entry names, one per line");
    }

    @Test
    void invokeUnknownToolThrows(@TempDir Path dir) {
        FilesystemToolProvider provider = providerFor(dir);

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("fs.delete", args("path", "x")),
                "a name this provider does not contribute is a programming error, not a silent no-op");
    }

    @Test
    void invokeOutsideTheWorkspaceIsRefused(@TempDir Path dir) {
        FilesystemToolProvider provider = providerFor(dir);

        assertThrows(WorkspaceEscapeException.class,
                () -> provider.invoke("fs.write", args("path", "../escape.txt", "content", "owned")),
                "workspace confinement still applies on the invoke() path");
    }
}
