package ai.forvum.core;

/**
 * The retrieval strategy a {@code MemoryProvider} applies when serving a {@code MemoryQuery}
 * (ULTRAPLAN section 4.3.6, the Context-Engineering Select pillar; DR-5). The strategy is carried on
 * {@link MemoryPolicy} and chosen per agent in {@code agents/<id>.json}, decoupling the agent from any
 * one retrieval mechanism.
 *
 * <ul>
 *   <li>{@link #VECTOR} — dense-embedding nearest-neighbour search.</li>
 *   <li>{@link #GRAPH} — relationship-graph traversal.</li>
 *   <li>{@link #METADATA} — structured metadata / keyword filtering, no embedding required.</li>
 *   <li>{@link #HYBRID} — a provider-defined blend (the {@link MemoryPolicy#defaults() default}).</li>
 *   <li>{@link #ITERATIVE} — the agentic-RAG orchestration strategy (#196): the host runs a BOUNDED
 *       retrieve → evaluate-sufficiency → decompose/re-query loop instead of one single-shot retrieval;
 *       each underlying provider call runs the {@link #HYBRID} blend. OPT-IN — single-shot stays the
 *       default.</li>
 *   <li>{@link #NONE} — retrieval disabled; the provider returns no hits. The only strategy under which
 *       an empty tier set is legal (DR-5 DP-5).</li>
 * </ul>
 */
public enum RetrievalStrategy {
    VECTOR,
    GRAPH,
    METADATA,
    HYBRID,
    ITERATIVE,
    NONE
}
