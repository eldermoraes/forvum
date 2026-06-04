package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.EventType;
import ai.forvum.core.Role;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.EpisodicMemoryEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import jakarta.transaction.Transactional;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * The bound agent's three-tier memory (ULTRAPLAN sections 4.2 / 5.1, the Write pillar): conversational
 * {@code messages}, the {@code episodic_memory} event log, and long-term {@code semantic_memory} facts.
 * Every write is scoped to {@link CurrentAgent#CURRENT_AGENT}, so an agent only ever touches its own
 * rows. Semantic {@code embedding} vectors are deferred this cycle (left null) — population + similarity
 * retrieval land with the milestone that first reads semantic memory (M7 AC-7).
 */
@AgentScoped
public class AgentMemory {

    @Transactional
    public void addUserMessage(String sessionId, String content) {
        persistMessage(sessionId, Role.USER, content);
    }

    @Transactional
    public void addAssistantMessage(String sessionId, String content) {
        persistMessage(sessionId, Role.ASSISTANT, content);
    }

    /** Conversational history for {@code sessionId}, oldest first, as LangChain4j messages. */
    public List<ChatMessage> messages(String sessionId) {
        List<MessageEntity> rows = MessageEntity.list(
                "sessionId = ?1 and agentId = ?2 order by id", sessionId, agentId());
        List<ChatMessage> history = new ArrayList<>(rows.size());
        for (MessageEntity row : rows) {
            switch (Role.fromDbValue(row.role)) {
                case USER -> history.add(UserMessage.from(row.content));
                case ASSISTANT -> history.add(AiMessage.from(row.content));
                case SYSTEM -> history.add(SystemMessage.from(row.content));
                case TOOL -> { /* tool messages are not surfaced in v0.1 conversational history */ }
            }
        }
        return history;
    }

    @Transactional
    public void recordObservation(String sessionId, String content) {
        EpisodicMemoryEntity event = new EpisodicMemoryEntity();
        event.agentId = agentId();
        event.sessionId = sessionId;
        event.eventType = EventType.OBSERVATION.dbValue();
        event.content = content;
        event.createdAt = System.currentTimeMillis();
        event.persist();
    }

    /** Record a long-term fact. The {@code embedding} vector is left null this cycle (M7 AC-7). */
    @Transactional
    public void recordFact(String key, String value, String source) {
        long now = System.currentTimeMillis();
        SemanticMemoryEntity fact = new SemanticMemoryEntity();
        fact.agentId = agentId();
        fact.key = key;
        fact.value = value;
        fact.embedding = null;
        fact.source = source;
        fact.createdAt = now;
        fact.updatedAt = now;
        fact.persist();
    }

    private void persistMessage(String sessionId, Role role, String content) {
        MessageEntity message = new MessageEntity();
        message.sessionId = sessionId;
        message.agentId = agentId();
        message.role = role.dbValue();
        message.content = content;
        message.tokens = null;
        message.createdAt = System.currentTimeMillis();
        message.persist();
    }

    private static String agentId() {
        return CurrentAgent.CURRENT_AGENT.get().value();
    }
}
