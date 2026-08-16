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
 * returning OVERSIZED results must stay under a target window on the {@link ChatRequest#messages()}
 * the model actually sees mid-turn (captured by a scripted model — the M18 pattern), the pruning must
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
    void longToolLoopTurnStaysUnderTheTargetWindowMidTurn() {
        // Three rounds of 20k-char tool results (60k raw) against a 500-char threshold: every tool
        // result the model sees mid-turn must already be pruned, keeping the whole conversation under
        // a target window instead of accumulating raw output.
        ScriptedChatModel model = new ScriptedChatModel(
                readCall(1), readCall(2), readCall(3), AiMessage.from("done"));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can read files"),
                UserMessage.from("read the three files"));

        String reply = graph().run(new GraphTurnRequest("s197", new AgentId("main"), model,
                List.of(FS_READ), seed, null, COMPRESS_ONLY));

        assertEquals("done", reply);
        assertEquals(4, model.seen.size(), "the pruner makes NO model calls of its own");

        for (int call = 1; call < model.seen.size(); call++) {
            for (ChatMessage message : model.seen.get(call)) {
                if (message instanceof ToolExecutionResultMessage result) {
                    assertTrue(result.text().length() <= THRESHOLD,
                            "every tool result the model sees mid-turn is within the threshold (call "
                                    + call + " carried " + result.text().length() + " chars)");
                    assertTrue(result.text().startsWith(HEAD), "head kept");
                    assertTrue(result.text().endsWith(TAIL), "tail kept");
                    assertTrue(result.text().contains(MidTurnPruner.ELISION_MARKER), "middle elided");
                }
            }
        }

        // The target window: seed + per-round (assistant tool-call stub + pruned result). 60k of raw
        // tool output must never reach the model — the final call stays under a few KB.
        int finalWindowChars = model.seen.get(3).stream().mapToInt(m -> m.toString().length()).sum();
        assertTrue(finalWindowChars < 5_000,
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
