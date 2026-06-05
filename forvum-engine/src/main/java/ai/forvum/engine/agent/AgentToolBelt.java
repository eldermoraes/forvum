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

    private List<ToolSpec> filtered;

    /** The agent's {@code allowedTools} globs (immutable, from its persona). */
    public List<String> globs() {
        return registry.persona(CurrentAgent.CURRENT_AGENT.get()).allowedTools();
    }

    /**
     * The filtered tools this agent may call — the global registry intersected with {@link #globs()}.
     * Cached on first use: the bean is {@code @AgentScoped} and the result is immutable, so a concurrent
     * first read at worst recomputes the same list (no lock, no pinning).
     */
    public List<ToolSpec> tools() {
        if (filtered == null) {
            filtered = ToolFilter.filter(globs(), toolRegistry.all());
        }
        return filtered;
    }
}
