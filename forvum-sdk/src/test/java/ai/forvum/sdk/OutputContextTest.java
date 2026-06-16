package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/** Invariants of the {@link OutputContext} record + the {@link HookLayer} enum (P2-OUTPUTGUARD #48). */
class OutputContextTest {

    @Test
    void carriesItsFields() {
        UUID turn = UUID.randomUUID();
        AgentId agent = new AgentId("main");
        OutputContext ctx = new OutputContext(HookLayer.PRE_CHANNEL_EMIT, agent, turn);
        assertEquals(HookLayer.PRE_CHANNEL_EMIT, ctx.layer());
        assertEquals(agent, ctx.agentId());
        assertEquals(turn, ctx.turnId());
    }

    @Test
    void rejectsANullLayer() {
        assertThrows(IllegalStateException.class,
                () -> new OutputContext(null, new AgentId("main"), UUID.randomUUID()));
    }

    @Test
    void hookLayerHasThePreChannelEmitSeamPlusTheTwoReservedOnes() {
        assertEquals(3, HookLayer.values().length);
        assertEquals(HookLayer.PRE_CHANNEL_EMIT, HookLayer.valueOf("PRE_CHANNEL_EMIT"));
        assertEquals(HookLayer.PRE_MEMORY_WRITE, HookLayer.valueOf("PRE_MEMORY_WRITE"));
        assertEquals(HookLayer.PRE_TOOL_CALL, HookLayer.valueOf("PRE_TOOL_CALL"));
    }
}
