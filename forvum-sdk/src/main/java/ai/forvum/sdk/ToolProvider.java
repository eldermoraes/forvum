package ai.forvum.sdk;

/**
 * SPI a tool plugin implements to contribute tools (and their {@code ai.forvum.core.PermissionScope})
 * to the global tool registry (ULTRAPLAN section 2.2, Layer 1). Sealed: third parties extend
 * {@link AbstractToolProvider}. The contribution/execution methods (carrying
 * {@code ai.forvum.core.ToolSpec}) are added by the tool milestones (M13-M14); M3 fixes only the
 * structural contract and the id.
 */
public sealed interface ToolProvider permits AbstractToolProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();
}
