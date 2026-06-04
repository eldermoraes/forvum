package ai.forvum.engine.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** Unit test for the {@link CurrentAgent} ScopedValue bindings. Pure {@code *Test}, no Quarkus boot. */
class CurrentAgentTest {

    @Test
    void agentIsUnboundByDefault() {
        assertFalse(CurrentAgent.CURRENT_AGENT.isBound());
    }

    @Test
    void agentIsVisibleWithinBindingAndTornDownAfter() throws Exception {
        AgentId id = new AgentId("x");

        String seen = ScopedValue.where(CurrentAgent.CURRENT_AGENT, id)
                .call(() -> CurrentAgent.CURRENT_AGENT.get().value());

        assertEquals("x", seen);
        assertFalse(CurrentAgent.CURRENT_AGENT.isBound(), "binding must be torn down after the lambda");
    }

    @Test
    void turnBindsNestedUnderAgent() throws Exception {
        AgentId id = new AgentId("x");
        UUID turn = UUID.randomUUID();

        UUID seen = ScopedValue.where(CurrentAgent.CURRENT_AGENT, id)
                .where(CurrentAgent.CURRENT_TURN, turn)
                .call(() -> {
                    assertTrue(CurrentAgent.CURRENT_AGENT.isBound());
                    return CurrentAgent.CURRENT_TURN.get();
                });

        assertEquals(turn, seen);
        assertFalse(CurrentAgent.CURRENT_TURN.isBound());
    }
}
