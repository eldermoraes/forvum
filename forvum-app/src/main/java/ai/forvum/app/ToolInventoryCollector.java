package ai.forvum.app;

import ai.forvum.core.ToolSpec;
import ai.forvum.engine.doctor.ToolInventory;
import ai.forvum.sdk.ToolProvider;
import ai.forvum.tools.mcp.McpBridgeToolProvider;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers the assembled app's registered tools from {@code Instance<ToolProvider>} (#184) — the same source
 * {@code forvum tools} and {@code forvum doctor} both read, extracted here so the two cannot drift. It runs
 * fine as a {@code CommandMode} one-shot: {@code ToolRegistry.onStart} deliberately does NOT materialize the
 * registry for a one-shot, so both commands discover directly through CDI, exactly as {@code McpListCommand}
 * injects the bridge on demand.
 *
 * <p>The MCP bridge instance is SKIPPED ({@code instanceof} on the concrete type, the [M12] {@code .class}
 * discipline): its {@code tools()} is a blocking network connect per configured server (P2-13), which must
 * never be paid on a one-shot listing. Configured MCP servers are surfaced by {@code ToolsCommand} from the
 * {@code mcp-servers/} files instead (no connect).
 */
final class ToolInventoryCollector {

    private ToolInventoryCollector() {
    }

    /** Discover every non-bridge provider's specs, owners, and config gaps into a {@link Catalog}. */
    static Catalog collect(Instance<ToolProvider> providers) {
        List<ToolSpec> specs = new ArrayList<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, String> configGaps = new LinkedHashMap<>();
        for (ToolProvider provider : providers) {
            if (provider instanceof McpBridgeToolProvider) {
                continue; // never connect on a listing (P2-13)
            }
            for (ToolSpec spec : provider.tools()) {
                specs.add(spec);
                owners.put(spec.name(), provider.extensionId());
            }
            configGaps.putAll(provider.configGaps());
        }
        return new Catalog(List.copyOf(specs), Map.copyOf(owners), Map.copyOf(configGaps));
    }

    /**
     * The gathered tool catalog: every registered spec, the owning extension id per tool name, and the
     * tool-name → config-gap hint map. {@link #toInventory()} narrows it to the {@link ToolInventory} the
     * engine's {@code ConfigDoctor} consumes.
     */
    record Catalog(List<ToolSpec> specs, Map<String, String> owners, Map<String, String> configGaps) {

        ToolInventory toInventory() {
            return new ToolInventory(specs, configGaps);
        }
    }
}
