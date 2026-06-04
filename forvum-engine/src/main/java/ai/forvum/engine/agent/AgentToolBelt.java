package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.engine.context.CurrentAgent;

import jakarta.inject.Inject;

import java.util.List;

/**
 * The bound agent's allowed-tool glob list — the narrowing target for capability filtering. This cycle
 * exposes the globs only; M13's {@code ToolRegistry} intersects them against the global tool set to
 * produce the filtered {@code List<ToolSpec>} the LLM actually sees (ULTRAPLAN section 5.3). Resolved
 * per the {@link CurrentAgent#CURRENT_AGENT} binding, so it is {@code @AgentScoped}.
 */
@AgentScoped
public class AgentToolBelt {

    @Inject
    AgentRegistry registry;

    /** The agent's {@code allowedTools} globs (immutable, from its persona). No filtering yet (M13). */
    public List<String> globs() {
        return registry.persona(CurrentAgent.CURRENT_AGENT.get()).allowedTools();
    }
}
