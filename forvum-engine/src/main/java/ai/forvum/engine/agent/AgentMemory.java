package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.BlockType;
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

    /**
     * Persist a completed turn atomically: the user message, the assistant reply, and a turn
     * observation in one transaction. Called by {@link Agent#respond} only after a successful turn,
     * so a failed turn leaves no orphan conversational rows (the failed attempt is still ledgered
     * separately in {@code provider_calls} by the fallback decorator).
     *
     * @return the persisted assistant message id — the turn id a {@code capr_events} row references (M18)
     */
    @Transactional
    public long recordTurn(String sessionId, String userText, String assistantText) {
        persistMessage(sessionId, Role.USER, userText);
        MessageEntity assistant = persistMessage(sessionId, Role.ASSISTANT, assistantText);
        persistObservation(sessionId, "turn completed");
        return assistant.id;
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
        persistObservation(sessionId, content);
    }

    /**
     * Record a long-term fact, upserting on {@code (agent_id, key)} so a re-write updates the existing
     * row rather than violating the table's UNIQUE constraint. The {@code embedding} vector is left null
     * this cycle (M7 AC-7); {@code updated_at} is bumped on every update.
     */
    @Transactional
    public void recordFact(String key, String value, String source) {
        long now = System.currentTimeMillis();
        String agentId = agentId();
        // Filter by key in-memory (per-agent fact sets are small in a single-user deployment) to avoid
        // referencing the SQL-reserved column name "key" in a JPQL where-clause.
        SemanticMemoryEntity existing = SemanticMemoryEntity.<SemanticMemoryEntity>list("agentId = ?1", agentId)
                .stream().filter(fact -> fact.key.equals(key)).findFirst().orElse(null);
        if (existing != null) {
            existing.value = value;
            existing.source = source;
            existing.updatedAt = now;
            return;
        }
        SemanticMemoryEntity fact = new SemanticMemoryEntity();
        fact.agentId = agentId;
        fact.key = key;
        fact.value = value;
        fact.embedding = null;
        fact.source = source;
        fact.createdAt = now;
        fact.updatedAt = now;
        fact.persist();
    }

    private MessageEntity persistMessage(String sessionId, Role role, String content) {
        MessageEntity message = new MessageEntity();
        message.sessionId = sessionId;
        message.agentId = agentId();
        message.role = role.dbValue();
        message.content = content;
        message.tokens = null;
        // v0.1 conversational writes are plain turn messages; reasoning/artifact/tool-execution blocks
        // (the discriminator session compaction strips/retains) are written by the M18 graph path.
        message.blockType = BlockType.TURN_MESSAGE.dbValue();
        message.createdAt = System.currentTimeMillis();
        message.persist();
        return message;
    }

    private void persistObservation(String sessionId, String content) {
        EpisodicMemoryEntity event = new EpisodicMemoryEntity();
        event.agentId = agentId();
        event.sessionId = sessionId;
        event.eventType = EventType.OBSERVATION.dbValue();
        event.content = content;
        event.createdAt = System.currentTimeMillis();
        event.persist();
    }

    private static String agentId() {
        return CurrentAgent.CURRENT_AGENT.get().value();
    }
}
