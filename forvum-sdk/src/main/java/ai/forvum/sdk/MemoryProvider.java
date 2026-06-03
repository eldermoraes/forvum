package ai.forvum.sdk;

/**
 * SPI a memory-host plugin implements so the Select pillar can retrieve relevant memory (vector,
 * graph, metadata, or hybrid) without coupling the agent to a retrieval strategy (ULTRAPLAN
 * section 2.2, Layer 1; CONTEXT-ENGINEERING Select pillar). Sealed: third parties extend
 * {@link AbstractMemoryProvider}. The retrieval method (driven by {@code ai.forvum.core.MemoryPolicy})
 * is settled by DR-5 and detailed in a later milestone; M3 fixes only the structural contract and
 * the id.
 */
public sealed interface MemoryProvider permits AbstractMemoryProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();
}
