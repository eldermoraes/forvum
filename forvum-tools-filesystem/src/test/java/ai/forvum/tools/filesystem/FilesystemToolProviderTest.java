package ai.forvum.tools.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Contract test for {@link FilesystemToolProvider}: it contributes the three filesystem tools with their
 * {@link PermissionScope}s, so the engine's ToolRegistry indexes {@code fs.read}/{@code fs.write}/
 * {@code fs.list} and a persona's {@code allowedTools} globs (e.g. {@code fs.*}) can reference them.
 */
class FilesystemToolProviderTest {

    private final FilesystemToolProvider provider = new FilesystemToolProvider();

    @Test
    void contributesTheThreeFilesystemTools() {
        assertEquals("filesystem", provider.extensionId());
        assertEquals(List.of("fs.read", "fs.write", "fs.list"),
                provider.tools().stream().map(ToolSpec::name).toList());
    }

    @Test
    void declaresTheExpectedScopes() {
        var scopeByName = provider.tools().stream()
                .collect(Collectors.toMap(ToolSpec::name, ToolSpec::requiredScope));

        assertEquals(PermissionScope.FS_READ, scopeByName.get("fs.read"));
        assertEquals(PermissionScope.FS_WRITE, scopeByName.get("fs.write"));
        assertEquals(PermissionScope.FS_READ, scopeByName.get("fs.list"),
                "fs.list reads the directory, so it requires FS_READ (no FS_LIST scope exists)");
    }
}
