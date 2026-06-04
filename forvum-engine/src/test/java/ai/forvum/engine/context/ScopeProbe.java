package ai.forvum.engine.context;

import ai.forvum.core.AgentScoped;

/**
 * Concrete {@code @AgentScoped} bean used by {@link AgentContextIsolationTest} to prove per-agent
 * instance isolation: {@link #identity()} returns the identity hash of the contextual instance
 * resolved for the currently bound agent.
 */
@AgentScoped
public class ScopeProbe {

    public int identity() {
        return System.identityHashCode(this);
    }
}
