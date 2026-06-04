package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.ProviderCallEntity;

import org.junit.jupiter.api.Test;

/**
 * The minimal single-agent turn (ULTRAPLAN section 5.5, pre-graph): {@link Agent#respond} runs the
 * {@code faker} agent through {@link FakeModelProvider}, returning a non-empty reply and leaving the
 * turn fully ledgered — two {@code messages} rows (user + assistant), one {@code provider_calls} row,
 * and one {@code episodic_memory} observation. This is the engine-side analogue of M9's gated
 * "scripted turn through AgentRegistry" e2e.
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
    }
}
