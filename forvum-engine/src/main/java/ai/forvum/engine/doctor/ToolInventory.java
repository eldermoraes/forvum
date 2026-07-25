package ai.forvum.engine.doctor;

import ai.forvum.core.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * The registered-tool snapshot {@code forvum doctor} needs to flag a belted-but-unconfigured tool (#184):
 * every contributed {@link ToolSpec} plus the tool-name → actionable config-gap hint map gathered from the
 * providers' {@code ToolProvider.configGaps()}. The assembled app supplies the real inventory (from
 * {@code Instance<ToolProvider>}, skipping the MCP bridge); a unit test passes an explicit one, and the
 * config-editor / dev path passes {@link #empty()} so its {@code ConfigDoctor} run is byte-identical.
 *
 * <p>Never serialized (an in-process value the app hands to {@link ConfigDoctor}, the {@code GraphTurnRequest}
 * precedent), so it carries no {@code @RegisterForReflection}.
 *
 * @param specs      every registered tool's spec (belt membership is computed against these)
 * @param configGaps tool-name → one-line "not configured" hint for a present-but-unconfigured tool
 */
public record ToolInventory(List<ToolSpec> specs, Map<String, String> configGaps) {

    public ToolInventory {
        specs = List.copyOf(specs);
        configGaps = Map.copyOf(configGaps);
    }

    /** The empty inventory: no tools, no gaps — {@code ConfigDoctor}'s belt-gap check is then a no-op. */
    public static ToolInventory empty() {
        return new ToolInventory(List.of(), Map.of());
    }
}
