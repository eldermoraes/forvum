package ai.forvum.sdk;

import ai.forvum.core.ToolSpec;

import java.util.List;

/**
 * SPI a tool plugin implements to contribute tools (and their {@code ai.forvum.core.PermissionScope})
 * to the global tool registry (ULTRAPLAN section 2.2, Layer 1). Sealed: third parties extend
 * {@link AbstractToolProvider}. M3 fixed only the structural contract and the id; the contribution
 * method ({@link #tools()}, carrying {@link ToolSpec}) is added here by the M13 prelude that M14's
 * filesystem plugin implements. Execution stays in the engine's ToolExecutor, never on this SPI, so
 * the contract carries only {@code forvum-core} types (no Quarkus, no AI library).
 */
public sealed interface ToolProvider permits AbstractToolProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();

    /**
     * The tools this extension contributes to the global ToolRegistry. Each {@link ToolSpec} carries the
     * {@code ai.forvum.core.PermissionScope} the engine's ToolExecutor enforces before invocation, and
     * the persona's {@code allowedTools} globs later filter these into an agent's tool belt.
     *
     * <p>The engine's ToolRegistry discovers every {@code ToolProvider} via CDI and registers each
     * returned spec under its {@link ToolSpec#name()}; implementations return the static list of tools
     * they expose (they do not touch the registry, filtering, or execution — those are the engine's
     * concern, ULTRAPLAN section 5.3).
     */
    List<ToolSpec> tools();
}
