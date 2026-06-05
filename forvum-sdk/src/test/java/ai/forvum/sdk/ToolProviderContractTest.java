package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the {@link ToolProvider} contribution method (ULTRAPLAN section 2.2 / 5.3): a tool
 * plugin contributes its {@link ToolSpec}s — each carrying its {@link PermissionScope} — to the engine's
 * global ToolRegistry. The method lands in the SDK as the M13 prelude because the engine's ToolRegistry
 * consumes it and M13 merges before M14 — which implements it for the filesystem tools. Carries only
 * {@code forvum-core} types, so the SDK stays Quarkus- and AI-library-free.
 */
class ToolProviderContractTest {

    @Test
    void toolsReturnsTheContributedSpecs() {
        ToolSpec read = new ToolSpec("a.read", "Read a thing", PermissionScope.FS_READ, "{}");
        List<ToolSpec> contributed = List.of(read);
        ToolProvider provider = new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "fake";
            }

            @Override
            public List<ToolSpec> tools() {
                return contributed;
            }
        };

        List<ToolSpec> tools = provider.tools();

        assertEquals(1, tools.size());
        assertSame(read, tools.get(0));
    }
}
