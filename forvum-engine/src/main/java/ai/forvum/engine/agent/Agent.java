package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.Persona;
import ai.forvum.engine.context.CurrentAgent;

import jakarta.inject.Inject;

/**
 * The {@code @AgentScoped} facade for a live agent: it aggregates the agent's {@link Persona} and its
 * {@link AgentToolBelt} (and, in later M7 slices, its memory and a {@code respond} turn). Isolated per
 * {@link CurrentAgent#CURRENT_AGENT}, so two agents bound on two virtual threads resolve distinct
 * instances and one agent always resolves the same cached instance (ULTRAPLAN section 5.1).
 */
@AgentScoped
public class Agent {

    @Inject
    AgentRegistry registry;

    @Inject
    AgentToolBelt toolBelt;

    /** This agent's persona (system prompt + structural spec), for the currently bound agent. */
    public Persona persona() {
        return registry.persona(CurrentAgent.CURRENT_AGENT.get());
    }

    /** The agent's allowed-tool glob belt. */
    public AgentToolBelt toolBelt() {
        return toolBelt;
    }

    /** Identity of the resolved per-agent instance — lets tests assert per-agent isolation/caching. */
    public int identity() {
        return System.identityHashCode(this);
    }
}
