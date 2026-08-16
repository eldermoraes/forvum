package ai.forvum.engine.session.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.ModelRef;
import ai.forvum.engine.routing.LlmSelector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit contract for {@link DefaultSummarizer} (#180): the production summarizer must (1) short-circuit
 * an empty dropped-run to {@code ""} without resolving any model, (2) resolve the configured
 * summarizer model through the {@link LlmSelector} routing seam attributed to the {@code compaction}
 * agent/session, and (3) send the pinned system prompt plus the dropped contents joined by blank lines.
 * Direct instantiation with a scripted selector (no Quarkus boot, no network) keeps this measured by
 * the Surefire-only coverage gate (X3).
 */
class DefaultSummarizerTest {

    @Test
    void anEmptyDroppedRunSummarizesToEmptyWithoutResolvingAModel() {
        RecordingSelector selector = new RecordingSelector("never used");
        DefaultSummarizer summarizer = summarizer(selector);

        assertEquals("", summarizer.summarize(List.of()),
                "an empty dropped run must summarize to the empty string");
        assertEquals(0, selector.resolutions, "no model may be resolved for an empty dropped run");
    }

    @Test
    void summarizesTheDroppedRunThroughTheConfiguredProxyModel() {
        RecordingSelector selector = new RecordingSelector("a dense summary");
        DefaultSummarizer summarizer = summarizer(selector);

        String summary = summarizer.summarize(List.of("first dropped turn", "second dropped turn"));

        assertEquals("a dense summary", summary, "the proxy model's reply is the summary");
        assertEquals(1, selector.resolutions, "exactly one model resolution per pass");
        assertEquals(ModelRef.parse("fake:proxy"), selector.lastRef,
                "the configured summarizer model must be resolved, not invented");
        assertEquals("compaction", selector.lastAgentId, "the ledger attributes to the compaction agent");
        assertEquals("compaction", selector.lastSessionId, "the ledger attributes to the compaction session");
        assertTrue(selector.lastSystem.contains("compress prior conversation turns"),
                "the pinned compaction system prompt must be sent");
        assertEquals("first dropped turn\n\nsecond dropped turn", selector.lastUser,
                "dropped contents are joined oldest-first with blank lines");
    }

    private static DefaultSummarizer summarizer(RecordingSelector selector) {
        DefaultSummarizer summarizer = new DefaultSummarizer();
        summarizer.llmSelector = selector;
        summarizer.summarizerModel = "fake:proxy";
        return summarizer;
    }

    /** A scripted {@link LlmSelector} recording the resolution and the messages the summarizer sends. */
    private static final class RecordingSelector extends LlmSelector {
        private final String reply;
        int resolutions;
        ModelRef lastRef;
        String lastAgentId;
        String lastSessionId;
        String lastSystem;
        String lastUser;

        private RecordingSelector(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatModel resolve(ModelRef ref, String agentId, String sessionId, CostBudget budget) {
            resolutions++;
            lastRef = ref;
            lastAgentId = agentId;
            lastSessionId = sessionId;
            return new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    lastSystem = request.messages().stream()
                            .filter(SystemMessage.class::isInstance)
                            .map(m -> ((SystemMessage) m).text())
                            .findFirst().orElse("");
                    lastUser = request.messages().stream()
                            .filter(UserMessage.class::isInstance)
                            .map(m -> ((UserMessage) m).singleText())
                            .findFirst().orElse("");
                    return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
                }
            };
        }
    }
}
