package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.compress.MidTurnPruner;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.plan.InMemoryPlanStore;
import ai.forvum.engine.tools.ToolTestFixtures;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph-level test for #197 mid-turn context pruning: a long synthetic turn whose tool loop keeps
 * returning OVERSIZED results must have its AGED results (outside the D3.3 recency window) pruned on
 * the {@link ChatRequest#messages()} the model actually sees mid-turn (captured by a scripted model — the M18 pattern), the pruning must
 * be tail-region-only (the seeded prefix reaches the model byte-identical on every round), and the
 * whole pass must run with no model call and no IO (the scripted model records every call it gets;
 * the count proves no extra call happened).
 */
class SupervisorGraphMidTurnPruneTest {

    private static final int THRESHOLD = 500;

    private static final String HEAD = "HEAD-SENTINEL ";
    private static final String TAIL = " TAIL-SENTINEL";

    /** ~20k chars per tool result — 40x the threshold, blowing any window without mid-turn pruning. */
    private static final String OVERSIZED_RESULT =
            HEAD + "x".repeat(20_000 - HEAD.length() - TAIL.length()) + TAIL;

    /** Retrieval off (no selector needed) but the Compress knob set — the #197 governing knob. */
    private static final MemoryPolicy COMPRESS_ONLY =
            new MemoryPolicy(RetrievalStrategy.NONE, Set.of(), 1, 0.0, THRESHOLD);

    private static final ToolSpec FS_READ = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");

    /** A {@link ChatModel} that returns a queued sequence of replies AND records the messages it saw. */
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<AiMessage> replies;
        private final List<List<ChatMessage>> seen = new ArrayList<>();

        private ScriptedChatModel(AiMessage... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            seen.add(List.copyOf(request.messages()));
            return ChatResponse.builder().aiMessage(replies.poll()).build();
        }
    }

    private static ToolProvider oversizedProvider() {
        return new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "fake";
            }

            @Override
            public List<ToolSpec> tools() {
                return List.of(FS_READ);
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                return OVERSIZED_RESULT;
            }
        };
    }

    private static AiMessage readCall(int round) {
        return AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-" + round).name("fs.read")
                        .arguments("{\"path\":\"/f" + round + "\"}").build()))
                .build();
    }

    private SupervisorGraph graph() {
        SupervisorGraph graph = new SupervisorGraph();
        graph.toolCallBridge = ToolTestFixtures.bridge(new InMemoryToolInvocationRecorder(), oversizedProvider());
        graph.mapper = new ObjectMapper();
        graph.planStore = new InMemoryPlanStore();
        return graph;
    }

    @Test
    void longToolLoopTurnPrunesAgedResultsAndStaysBoundedMidTurn() {
        // Six rounds of 20k-char tool results (120k raw) against a 500-char threshold. Under the
        // post-audit D3.3 recency window (KEEP_LAST_ASSISTANTS = 3), round j's result becomes prunable
        // only once three newer assistant messages exist: at generate call k the model must see
        // r1..r(k-3) pruned and the last three rounds' results WHOLE — the current work always reaches
        // the model intact while aged output is elided, keeping the window bounded.
        ScriptedChatModel model = new ScriptedChatModel(
                readCall(1), readCall(2), readCall(3), readCall(4), readCall(5), readCall(6),
                AiMessage.from("done"));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can read files"),
                UserMessage.from("read the six files"));

        String reply = graph().run(new GraphTurnRequest("s197", new AgentId("main"), model,
                List.of(FS_READ), seed, null, COMPRESS_ONLY));

        assertEquals("done", reply);
        assertEquals(7, model.seen.size(), "the pruner makes NO model calls of its own");

        for (int call = 1; call < model.seen.size(); call++) {
            List<ToolExecutionResultMessage> results = model.seen.get(call).stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .toList();
            assertEquals(call, results.size());
            for (int j = 1; j <= results.size(); j++) {
                ToolExecutionResultMessage result = results.get(j - 1);
                if (j <= call - MidTurnPruner.KEEP_LAST_ASSISTANTS) {
                    assertTrue(result.text().length() <= THRESHOLD,
                            "an aged result (round " + j + " at call " + call + ") is pruned, carried "
                                    + result.text().length() + " chars");
                    assertTrue(result.text().startsWith(HEAD), "head kept");
                    assertTrue(result.text().endsWith(TAIL), "tail kept");
                    assertTrue(result.text().contains(MidTurnPruner.ELISION_MARKER), "middle elided");
                } else {
                    assertEquals(OVERSIZED_RESULT, result.text(),
                            "a within-window result (round " + j + " at call " + call
                                    + ") reaches the model whole (D3.3)");
                }
            }
        }

        // The bounded window: aged results collapse to <=THRESHOLD each, so only the last
        // KEEP_LAST_ASSISTANTS raw results ride the final call (~60k) — without pruning all six would
        // (~120k). The bound sits between the two.
        int finalWindowChars = model.seen.get(6).stream().mapToInt(m -> m.toString().length()).sum();
        assertTrue(finalWindowChars < 70_000,
                "the final mid-turn window must stay bounded, was " + finalWindowChars + " chars");
    }

    @Test
    void pruningIsTailRegionOnlyTheCachedPrefixReachesTheModelByteIdentical() {
        ScriptedChatModel model = new ScriptedChatModel(
                readCall(1), readCall(2), AiMessage.from("done"));
        SystemMessage system = SystemMessage.from("you can read files");
        UserMessage question = UserMessage.from("read the files");

        graph().run(new GraphTurnRequest("s197b", new AgentId("main"), model,
                List.of(FS_READ), List.of(system, question), null, COMPRESS_ONLY));

        // On EVERY round (including after pruning ran), the seeded prefix is byte-identical.
        for (List<ChatMessage> call : model.seen) {
            assertEquals(system.text(), ((SystemMessage) call.get(0)).text(),
                    "the cached prefix's system bytes are untouched");
            assertEquals(question.singleText(), ((UserMessage) call.get(1)).singleText(),
                    "the cached prefix's user bytes are untouched");
        }
    }

    @Test
    void noPolicyMeansNoPruningTheRawResultFeedsBack() {
        // Without the knob (null memoryPolicy → threshold 0) the pass is disabled — pre-#197 behavior.
        ScriptedChatModel model = new ScriptedChatModel(readCall(1), AiMessage.from("done"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("read"));

        graph().run(new GraphTurnRequest("s197c", new AgentId("main"), model, List.of(FS_READ), seed));

        ToolExecutionResultMessage result = model.seen.get(1).stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertEquals(OVERSIZED_RESULT, result.text(), "no knob, no pruning — the raw result feeds back");
    }
}
