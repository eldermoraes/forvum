package ai.forvum.engine.agent;

import ai.forvum.core.Persona;
import ai.forvum.engine.graph.CycleSpec;

/**
 * The §5.2 agent-registry value: the Layer-0 {@link Persona} (the pure-data structural config) plus the
 * engine-side {@link CycleSpec} graph-compilation directive (DR-8 DP-2, ULTRAPLAN §4.3.8). This wrapper
 * finally aligns the code with the long-standing §5.2/§7.1 prose ("{@code ConcurrentMap<AgentId,
 * AgentSpec>}") — the M13 stale-Files lesson, closed rather than re-documented.
 *
 * <p>The split is deliberate: contract-grade pure data ({@code fallbackModels}, {@code memoryPolicy},
 * {@code roles}, {@code identityId}) grew on {@code Persona} (Layer 0), while {@code cycle} — a directive
 * to the LangGraph4j compiler, an engine concern — stays here. {@link AgentRegistry#persona(AgentId)}
 * keeps returning {@code Persona} so existing callers are untouched; the cycle is reachable via
 * {@link AgentRegistry#spec(AgentId)} for the #51 declarative-cycle compiler.
 *
 * @param persona the structural agent config (required)
 * @param cycle   the declared reflection cycle, or {@code null} for the standard §5.5 supervisor graph
 */
public record AgentSpec(Persona persona, CycleSpec cycle) {

    public AgentSpec {
        if (persona == null) {
            throw new IllegalStateException("AgentSpec persona must be non-null.");
        }
    }
}
