package ai.forvum.sdk;

/**
 * SPI a model plugin implements to supply an LLM binding to the routing layer (ULTRAPLAN
 * section 2.2, Layer 1). Sealed: third parties extend {@link AbstractModelProvider}. The resolution
 * method ({@code ai.forvum.core.ModelRef} to a LangChain4j {@code ChatModel}) is added by the
 * provider milestones (M9-M12), which bring the LangChain4j types; M3 fixes only the structural
 * contract and the id.
 */
public sealed interface ModelProvider permits AbstractModelProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();
}
