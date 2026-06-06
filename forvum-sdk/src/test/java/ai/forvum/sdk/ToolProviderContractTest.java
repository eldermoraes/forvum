package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the {@link ToolProvider} SPI (ULTRAPLAN section 2.2 / 5.3 / 5.5): a tool plugin
 * contributes its {@link ToolSpec}s — each carrying its {@link PermissionScope} — to the engine's global
 * ToolRegistry ({@link ToolProvider#tools()}, the M13 prelude), and executes a permitted call by name
 * ({@link ToolProvider#invoke(String, Map)}, the M18 Option-A execution method). Both carry only
 * {@code java.*} + {@code forvum-core} types, so the SDK stays Quarkus- and AI-library-free.
 */
class ToolProviderContractTest {

    private static ToolProvider provider(List<ToolSpec> contributed) {
        return new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "fake";
            }

            @Override
            public List<ToolSpec> tools() {
                return contributed;
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                return "invoked:" + toolName + ":" + arguments.get("path");
            }
        };
    }

    @Test
    void toolsReturnsTheContributedSpecs() {
        ToolSpec read = new ToolSpec("a.read", "Read a thing", PermissionScope.FS_READ, "{}");
        List<ToolSpec> contributed = List.of(read);

        List<ToolSpec> tools = provider(contributed).tools();

        assertEquals(1, tools.size());
        assertSame(read, tools.get(0));
    }

    @Test
    void invokeDispatchesByNameWithArguments() {
        ToolProvider provider = provider(
                List.of(new ToolSpec("a.read", "Read a thing", PermissionScope.FS_READ, "{}")));

        assertEquals("invoked:a.read:notes.md", provider.invoke("a.read", Map.of("path", "notes.md")));
    }
}
