package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The three-tier write surface (ULTRAPLAN sections 4.2 / 5.1): {@link AgentMemory} writes the bound
 * agent's rows in {@code messages}, {@code episodic_memory}, and {@code semantic_memory} (the latter
 * with a deferred {@code embedding}, M7 AC-7), and reads conversational history back as LangChain4j
 * {@link ChatMessage}s. {@link SessionManager} ensures the {@code sessions} FK target exists first.
 * Real SQLite via the seeded temp home (CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class AgentMemoryTest {

    @Inject
    AgentMemory memory;

    @Inject
    SessionManager sessions;

    @Test
    @Transactional
    void writesAndReadsBackTheThreeMemoryTiers() throws Exception {
        AgentId main = new AgentId("main");
        String sessionId = "session-tiers";
        sessions.ensureSession(sessionId, main);

        ScopedValue.where(CurrentAgent.CURRENT_AGENT, main).run(() -> {
            memory.addUserMessage(sessionId, "hello");
            memory.addAssistantMessage(sessionId, "hi there");
            memory.recordObservation(sessionId, "turn completed");
            memory.recordFact("user.name", "Elder", "session-tiers");
        });

        List<ChatMessage> history = ScopedValue.where(CurrentAgent.CURRENT_AGENT, main)
                .call(() -> memory.messages(sessionId));
        assertEquals(2, history.size(), "messages tier: user + assistant");
        assertEquals("hello", ((UserMessage) history.get(0)).singleText());
        assertEquals("hi there", ((AiMessage) history.get(1)).text());

        assertEquals(2, MessageEntity.count("sessionId = ?1 and agentId = ?2", sessionId, "main"));
        assertEquals(1, EpisodicMemoryEntity.count("agentId = ?1 and sessionId = ?2", "main", sessionId));
        // Scope to the fact this test wrote (the shared DB accumulates other agents'/keys' facts).
        long userNameFacts = SemanticMemoryEntity.<SemanticMemoryEntity>list("agentId = ?1", "main")
                .stream().filter(f -> "user.name".equals(f.key)).count();
        assertEquals(1, userNameFacts, "exactly one 'user.name' fact for main");
    }

    @Test
    @Transactional
    void recordFactUpsertsByKeyRatherThanViolatingTheUniqueConstraint() throws Exception {
        AgentId agentId = new AgentId("fact-upsert-agent");

        ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId).run(() -> {
            memory.recordFact("user.city", "Sao Paulo", "turn-1");
            memory.recordFact("user.city", "Rio", "turn-2");
        });

        assertEquals(1, SemanticMemoryEntity.count("agentId = ?1", "fact-upsert-agent"),
                "a second fact with the same key updates the row, not a second insert");
        SemanticMemoryEntity row = SemanticMemoryEntity
                .<SemanticMemoryEntity>find("agentId = ?1", "fact-upsert-agent").firstResult();
        assertEquals("Rio", row.value, "the latest value wins on upsert");
    }
}
