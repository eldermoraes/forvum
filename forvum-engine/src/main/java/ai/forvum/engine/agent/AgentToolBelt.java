package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.ToolSpec;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.tools.ToolFilter;
import ai.forvum.engine.tools.ToolRegistry;

import jakarta.inject.Inject;

import java.util.List;

/**
 * The bound agent's tool belt: its {@code allowedTools} globs and the filtered {@link ToolSpec} subset
 * those globs select from the global {@link ToolRegistry} — the only tools the LLM ever sees (ULTRAPLAN
 * section 5.3). Resolved per the {@link CurrentAgent#CURRENT_AGENT} binding, so it is {@code @AgentScoped}.
 */
@AgentScoped
public class AgentToolBelt {

    @Inject
    AgentRegistry registry;

    @Inject
    ToolRegistry toolRegistry;

    /** The agent's {@code allowedTools} globs (immutable, from its persona). */
    public List<String> globs() {
        return registry.persona(CurrentAgent.CURRENT_AGENT.get()).allowedTools();
    }

    /**
     * The filtered tools this agent may call — the global registry intersected with {@link #globs()}.
     * Recomputed on each call from the CURRENT lease (#178): a turn calls this once, and two turns of the
     * same agent running on different generations each filter their own leased globs, so a
     * capability-reducing reload is never served a stale belt cached on the shared {@code @AgentScoped}
     * bean. The filter is a cheap glob match over the registry; no cross-turn cache, no lock, no pinning.
     */
    public List<ToolSpec> tools() {
        return ToolFilter.filter(globs(), toolRegistry.all());
    }
}
