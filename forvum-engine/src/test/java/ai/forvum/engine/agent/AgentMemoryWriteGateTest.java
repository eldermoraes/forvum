package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.graph.ReplayContext;
import ai.forvum.engine.graph.ReplayToolSource;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * The #175 write gate ({@link Agent#shouldWriteMemory}): the off-turn Write phase fires only for a real
 * interactive turn — a bound turn id (not cron/internal), not a replay (would pollute real memory), and
 * memory enabled for the agent ({@code strategy != NONE}). Each gate is exercised independently. Pure
 * static logic — no CDI boot.
 */
class AgentMemoryWriteGateTest {

    private static Persona persona(RetrievalStrategy strategy) {
        MemoryPolicy policy = strategy == RetrievalStrategy.NONE
                ? new MemoryPolicy(RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 8000)
                : MemoryPolicy.defaults();
        return new Persona(new AgentId("a"), "sys", List.of(), ModelRef.parse("fake:m"),
                null, null, null, null, List.of(), policy, List.of(), null);
    }

    @Test
    void firesForANormalInteractiveTurn() {
        assertTrue(Agent.shouldWriteMemory(UUID.randomUUID(), persona(RetrievalStrategy.HYBRID)),
                "a bound turn id, not a replay, memory enabled -> write");
    }

    @Test
    void skipsWhenThereIsNoBoundTurnId() {
        assertFalse(Agent.shouldWriteMemory(null, persona(RetrievalStrategy.HYBRID)),
                "no bound turn id (cron/internal turn) -> skip");
    }

    @Test
    void skipsWhenMemoryIsDisabledForTheAgent() {
        assertFalse(Agent.shouldWriteMemory(UUID.randomUUID(), persona(RetrievalStrategy.NONE)),
                "strategy NONE (memory off) -> skip, matching retrieval's consent");
    }

    @Test
    void skipsUnderReplay() {
        UUID turn = UUID.randomUUID();
        Persona persona = persona(RetrievalStrategy.HYBRID);

        boolean underReplay = ScopedValue.where(ReplayContext.CURRENT_REPLAY, new ReplayToolSource(List.of()))
                .call(() -> Agent.shouldWriteMemory(turn, persona));

        assertFalse(underReplay, "a diagnostic replay must not durably write facts -> skip");
    }
}
