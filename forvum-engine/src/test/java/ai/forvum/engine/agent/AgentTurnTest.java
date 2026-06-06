package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.ProviderCallEntity;

import org.junit.jupiter.api.Test;

/**
 * The single-agent turn through the M18 {@link ai.forvum.engine.graph.SupervisorGraph}: {@link Agent#respond}
 * runs the {@code faker} agent through {@link FakeModelProvider} (a direct, no-tool answer), returning a
 * non-empty reply and leaving the turn fully ledgered — two {@code messages} rows (user + assistant), one
 * {@code provider_calls} row, one {@code episodic_memory} observation, and one {@code capr_events} verdict.
 * A failed turn leaves no conversational/CAPR rows but still ledgers the attempt.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentTurnTest {

    @Inject
    AgentRegistry registry;

    @Test
    @Transactional
    void respondPersistsTheTurnAndReturnsAReply() throws Exception {
        AgentId faker = new AgentId("faker");
        Agent agent = registry.getOrCreate(faker);
        String sessionId = "turn-1";

        String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, faker)
                .call(() -> agent.respond(sessionId, "hello"));

        assertFalse(reply.isBlank(), "the turn must produce a non-empty assistant reply");
        assertEquals(2, MessageEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "faker"),
                "messages: user + assistant");
        assertEquals(1, ProviderCallEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "faker"),
                "one provider_calls ledger row");
        assertEquals(1, EpisodicMemoryEntity.count("agentId = ?1 and sessionId = ?2", "faker", sessionId),
                "one episodic observation");
        assertEquals(1, CaprEventEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "faker"),
                "one capr_events verdict written for the turn (M18)");
    }

    @Test
    void respondPersistsNoConversationalRowsWhenTheModelFailsButStillLedgersTheAttempt() throws Exception {
        AgentId boomer = new AgentId("boomer");
        Agent agent = registry.getOrCreate(boomer);
        String sessionId = "turn-boom";

        // Deliberately NOT @Transactional: respond() owns the turn's transaction boundary.
        assertThrows(RuntimeException.class, () ->
                ScopedValue.where(CurrentAgent.CURRENT_AGENT, boomer)
                        .call(() -> agent.respond(sessionId, "hi")));

        assertEquals(0, MessageEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "boomer"),
                "a failed turn must leave no orphan user/assistant rows");
        assertEquals(0, EpisodicMemoryEntity.count("agentId = ?1 and sessionId = ?2", "boomer", sessionId),
                "no turn observation on failure");
        assertEquals(1, ProviderCallEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "boomer"),
                "the failed attempt is still ledgered in provider_calls (audit survives)");
        assertEquals(0, CaprEventEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "boomer"),
                "no capr verdict on a failed turn");
    }
}
