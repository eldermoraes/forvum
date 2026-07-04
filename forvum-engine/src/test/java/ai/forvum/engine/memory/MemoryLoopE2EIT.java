package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.graph.GraphTurnRequest;
import ai.forvum.engine.graph.SupervisorGraph;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The full Write→Select loop of #175, end-to-end and Qdrant-free: a durable fact stated in one turn is
 * written (extract → embed → persist by the real {@link MemoryWriter}) and then, in a LATER turn/session,
 * retrieved by the real {@link SupervisorGraph} + {@link LocalMemoryProvider} and framed into the prompt as
 * {@code <retrieved_memory>} data — captured by a scripted model. Also proves identity scoping: another
 * identity's later turn never sees the fact. The async write seam is pinned same-thread for determinism.
 */
@QuarkusTest
@TestProfile(MemoryWriterTestProfile.class)
class MemoryLoopE2EIT {

    private static final AgentId MAIN = new AgentId("main");

    @Inject
    MemoryWriter writer;

    @Inject
    SupervisorGraph graph;

    /** A {@link ChatModel} that records the conversation it is handed, so we can inspect the assembled prompt. */
    private static final class CapturingModel implements ChatModel {
        private final String reply;
        private volatile List<ChatMessage> seen = List.of();

        private CapturingModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.seen = List.copyOf(request.messages());
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    @BeforeEach
    void reset() {
        writer.executor = Runnable::run; // synchronous, deterministic write
        writer.writeEnabled = true;
        QuarkusTransaction.requiringNew().run(() -> SemanticMemoryEntity.delete("agentId = ?1", MAIN.value()));
    }

    @Test
    void aFactStatedInOneTurnIsRetrievedAndFramedInALaterTurn() {
        writeTurnAs("u1", "I just moved to Berlin");

        CapturingModel model = new CapturingModel("Sure");
        selectTurnAs("u1", model, "where do I live?");

        assertTrue(userMessageContains(model.seen, "<retrieved_memory>"),
                "the later turn's prompt must carry a retrieved_memory data block");
        assertTrue(userMessageContains(model.seen, "Berlin"),
                "the fact written in the first turn must reach the later turn's model — without Qdrant");
    }

    @Test
    void aDifferentIdentityNeverSeesTheFactInALaterTurn() {
        writeTurnAs("u1", "I just moved to Berlin");

        CapturingModel model = new CapturingModel("ok");
        selectTurnAs("u2", model, "where do I live?");

        assertFalse(userMessageContains(model.seen, "Berlin"),
                "u2's later turn must never retrieve u1's fact (identity scoping)");
    }

    /** Turn 1: drive the off-turn Write phase directly for {@code identity}. */
    private void writeTurnAs(String identity, String userText) {
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, MAIN)
                .where(CurrentIdentity.CURRENT_IDENTITY_ID, identity)
                .run(() -> writer.writeFacts(MAIN, identity, UUID.randomUUID(), "sess-write", userText, "reply"));
    }

    /** Turn 2 (a later session): run the graph so its Select step retrieves + frames for {@code identity}. */
    private void selectTurnAs(String identity, CapturingModel model, String question) {
        List<ChatMessage> seed = new ArrayList<>();
        seed.add(SystemMessage.from("be helpful"));
        seed.add(UserMessage.from(question));
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, MAIN)
                .where(CurrentIdentity.CURRENT_IDENTITY_ID, identity)
                .where(CurrentAgent.CURRENT_TURN, UUID.randomUUID())
                .run(() -> graph.run(new GraphTurnRequest("sess-read", MAIN, model, List.of(), seed, null,
                        MemoryPolicy.defaults())));
    }

    private static boolean userMessageContains(List<ChatMessage> messages, String needle) {
        return messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .anyMatch(text -> text.contains(needle));
    }
}
