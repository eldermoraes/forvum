package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.sdk.ToolProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies the sandbox tool WIRES under Quarkus: ArC discovers it as a {@link ToolProvider} bean, the
 * {@link SandboxConfig} and {@link WorkspaceRoot} producers inject, and with no {@code tools/sandbox.json}
 * (the test {@code forvum.home} points at an absent dir) the tool is fail-closed — every invocation refuses
 * before any container launch. This is the inert/no-config posture the CI native no-config smoke depends on.
 * Boots Quarkus in-JVM; runs under Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class SandboxToolProviderWiringIT {

    @Inject
    Instance<ToolProvider> providers;

    private SandboxToolProvider sandbox() {
        for (ToolProvider provider : providers) {
            if (provider instanceof SandboxToolProvider sandbox) {
                return sandbox;
            }
        }
        throw new AssertionError("SandboxToolProvider was not discovered as a ToolProvider bean");
    }

    @Test
    void beanIsDiscoveredWithTheExpectedExtensionIdAndTool() {
        SandboxToolProvider provider = sandbox();
        assertNotNull(provider);
        assertEquals("sandbox", provider.extensionId());
        assertEquals("sandbox.run", provider.tools().get(0).name());
    }

    @Test
    void invokeIsFailClosedWithNoConfig() {
        // The test forvum.home has no tools/sandbox.json, so the tool must refuse (no image configured).
        assertThrows(SandboxExecException.class,
                () -> sandbox().invoke("sandbox.run", Map.of("code", "print(1)")),
                "an unconfigured sandbox refuses every call");
    }
}
