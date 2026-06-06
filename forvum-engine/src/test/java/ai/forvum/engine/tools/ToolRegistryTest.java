package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ToolProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ToolRegistry}: the global registry of every {@link ToolSpec} contributed by
 * every {@link ToolProvider} plugin (ULTRAPLAN section 5.3). Tool names are globally unique across
 * providers, so a duplicate name is a hard configuration error, not a silent overwrite — the same
 * putIfAbsent-and-throw discipline as {@code AgentRegistry.spawn}.
 */
class ToolRegistryTest {

    private static ToolSpec tool(String name, PermissionScope scope) {
        return new ToolSpec(name, "desc of " + name, scope, "{}");
    }

    private static ToolProvider provider(String extensionId, ToolSpec... specs) {
        return new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return extensionId;
            }

            @Override
            public List<ToolSpec> tools() {
                return List.of(specs);
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                return extensionId + ":" + toolName;
            }
        };
    }

    @Test
    void registersEveryContributedTool() {
        ToolSpec read = tool("a.read", PermissionScope.FS_READ);
        ToolSpec write = tool("a.write", PermissionScope.FS_WRITE);
        ToolRegistry registry = new ToolRegistry();

        registry.register(provider("a", read, write));

        assertEquals(2, registry.all().size());
        assertSame(read, registry.lookup("a.read"));
        assertSame(write, registry.lookup("a.write"));
        assertNull(registry.lookup("a.delete"), "an unregistered tool resolves to null");
    }

    @Test
    void providerForRoutesAToolNameToItsOwningProvider() {
        ToolProvider a = provider("a", tool("a.read", PermissionScope.FS_READ));
        ToolProvider b = provider("b", tool("b.write", PermissionScope.FS_WRITE));
        ToolRegistry registry = new ToolRegistry();
        registry.register(a);
        registry.register(b);

        assertSame(a, registry.providerFor("a.read"));
        assertSame(b, registry.providerFor("b.write"));
        assertNull(registry.providerFor("c.unknown"), "an unregistered tool has no owning provider");
    }

    @Test
    void rejectsDuplicateToolNameAcrossProviders() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(provider("a", tool("shared.tool", PermissionScope.FS_READ)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.register(provider("b", tool("shared.tool", PermissionScope.FS_WRITE))));

        assertTrue(ex.getMessage().contains("shared.tool"),
                "the message names the colliding tool, got: " + ex.getMessage());
    }
}
