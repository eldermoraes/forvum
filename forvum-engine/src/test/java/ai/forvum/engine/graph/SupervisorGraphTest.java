package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.tools.ToolCallBridge;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Integration test for the M18 {@link SupervisorGraph} (ULTRAPLAN section 5.5 Verify). Covers the MVP
 * paths: a direct (no-tool) answer; the ReAct tool loop ("fetch X then summarize") where the tool RESULT
 * must be fed back to the model; sub-agent spawn where the worker's digest must be merged back; a reply
 * that MIXES spawn_worker with a belt tool (both must be executed/audited and answered); and runaway
 * spawning (the in-graph MAX_ROUNDS cap, not LangGraph4j's recursion limit, must bind). The model is
 * scripted and CAPTURES the conversation it sees per call, so a regression that drops the tool result or
 * the worker digest from the model's input flips the test red. The tool provider is synthetic (the engine
 * is extension-agnostic).
 */
class SupervisorGraphTest {

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

    /** A model that spawns a distinct worker for the first {@code rounds} calls, then answers. */
    private static final class SpawningChatModel implements ChatModel {
        private final int rounds;
        private final String finalText;
        private int call;

        private SpawningChatModel(int rounds, String finalText) {
            this.rounds = rounds;
            this.finalText = finalText;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            AiMessage reply = call < rounds
                    ? AiMessage.builder().toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                            .id("sp-" + call).name("spawn_worker")
                            .arguments("{\"childId\":\"w" + call + "\",\"task\":\"t" + call + "\"}").build())).build()
                    : AiMessage.from(finalText);
            call++;
            return ChatResponse.builder().aiMessage(reply).build();
        }
    }

    private static final ToolSpec FS_READ = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");

    private static ToolProvider readProvider(String cannedResult) {
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
                return cannedResult;
            }
        };
    }

    /** Whether any {@link ToolExecutionResultMessage} in {@code messages} carries {@code text}. */
    private static boolean hasToolResult(List<ChatMessage> messages, String text) {
        return messages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .anyMatch(result -> result.text() != null && result.text().contains(text));
    }

    /** Records the workers spawned + driven, and returns a deterministic digest per worker. */
    private static final class FakeWorkerRunner implements WorkerRunner {
        private final List<AgentId> spawned = new CopyOnWriteArrayList<>();
        private final List<AgentId> ran = new CopyOnWriteArrayList<>();

        @Override
        public void spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
            spawned.add(childId);
        }

        @Override
        public String runWorker(AgentId childId, String task, String sessionId) {
            ran.add(childId);
            return childId.value() + " result for: " + task;
        }
    }

    private final FakeWorkerRunner workerRunner = new FakeWorkerRunner();

    private SupervisorGraph graphWith(InMemoryToolInvocationRecorder recorder, ToolProvider provider) {
        SupervisorGraph graph = new SupervisorGraph();
        graph.toolCallBridge = ToolTestFixtures.bridge(recorder, provider);
        graph.workerRunner = workerRunner;
        graph.mapper = new ObjectMapper();
        return graph;
    }

    @Test
    void directAnswerTurnReturnsTheModelReplyAndCallsNoTools() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        List<ChatMessage> seed = List.of(SystemMessage.from("be brief"), UserMessage.from("hi"));
        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"),
                new ScriptedChatModel(AiMessage.from("Hello there")), List.of(), seed));

        assertEquals("Hello there", reply);
        assertTrue(recorder.invocations().isEmpty(), "a direct answer executes no tools");
    }

    @Test
    void fetchThenSummarizeFeedsTheToolResultBackToTheModelAndAuditsTheCall() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("the answer is 42"));

        AiMessage toolCall = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-1").name("fs.read").arguments("{\"path\":\"x.txt\"}").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(toolCall, AiMessage.from("Summary: the answer is 42"));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can read files"),
                UserMessage.from("fetch x.txt then summarize"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(FS_READ), seed));

        assertEquals("Summary: the answer is 42", reply, "the model's post-tool synthesis is the final message");
        assertEquals(1, recorder.invocations().size(), "the tool call was executed once");
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status(), "and audited ok");
        assertTrue(hasToolResult(model.seen.get(1), "the answer is 42"),
                "the SECOND generate must see the tool result fed back (the ReAct boundary)");
    }

    @Test
    void spawnWorkerMergesTheWorkerDigestBackBeforeTheFinalSynthesis() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        AiMessage spawnCall = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("sp-1").name("spawn_worker")
                        .arguments("{\"childId\":\"researcher\",\"task\":\"find the answer\"}").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(spawnCall, AiMessage.from("Final: the worker found it"));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can delegate"),
                UserMessage.from("use a researcher sub-agent to find the answer"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed));

        assertEquals("Final: the worker found it", reply);
        assertEquals(List.of(new AgentId("researcher")), workerRunner.spawned, "the worker was materialized");
        assertEquals(List.of(new AgentId("researcher")), workerRunner.ran, "and driven (on a virtual thread)");
        assertTrue(hasToolResult(model.seen.get(1), "researcher result for: find the answer"),
                "the SECOND generate must see the worker's merged digest (the Isolate/reduce boundary)");
    }

    @Test
    void aReplyMixingSpawnWorkerAndABeltToolExecutesAuditsAndAnswersBoth() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("file body"));

        AiMessage mixed = AiMessage.builder()
                .toolExecutionRequests(List.of(
                        ToolExecutionRequest.builder().id("t-1").name("fs.read")
                                .arguments("{\"path\":\"x.txt\"}").build(),
                        ToolExecutionRequest.builder().id("sp-1").name("spawn_worker")
                                .arguments("{\"childId\":\"researcher\",\"task\":\"dig\"}").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(mixed, AiMessage.from("Done"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("read x and delegate"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(FS_READ), seed));

        assertEquals("Done", reply);
        assertEquals(1, recorder.invocations().size(), "the belt tool emitted alongside spawn_worker is still executed");
        assertEquals("fs.read", recorder.invocations().get(0).toolName());
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status(), "and audited (R3 — no bypass)");
        assertEquals(List.of(new AgentId("researcher")), workerRunner.ran, "the worker also ran");
        assertTrue(hasToolResult(model.seen.get(1), "file body"), "the belt-tool result reaches the model");
        assertTrue(hasToolResult(model.seen.get(1), "researcher result for: dig"), "the worker digest reaches the model");
    }

    @Test
    void runawaySpawningDegradesViaMaxRoundsRatherThanThrowingOnTheRecursionLimit() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // 7 spawn rounds = ~30 node executions, well past LangGraph4j's default recursionLimit (25);
        // the explicit recursionLimit must let the in-graph MAX_ROUNDS cap bind so the turn completes.
        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"),
                new SpawningChatModel(7, "Finished"), List.of(),
                List.of(SystemMessage.from("sys"), UserMessage.from("keep delegating"))));

        assertEquals("Finished", reply, "the turn completes via MAX_ROUNDS instead of throwing on the recursion limit");
        assertEquals(7, workerRunner.ran.size(), "all seven workers ran");
    }

    @Test
    void malformedSpawnArgumentsDegradeGracefullyWithoutSpawningAWorker() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        AiMessage badSpawn = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("sp-1").name("spawn_worker").arguments("{not valid json").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(badSpawn, AiMessage.from("Recovered"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("delegate"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed));

        assertEquals("Recovered", reply, "a malformed spawn_worker call returns an error to the model, not a turn failure");
        assertTrue(workerRunner.spawned.isEmpty(), "no worker is spawned on malformed arguments");
        assertTrue(hasToolResult(model.seen.get(1), "spawn_worker"),
                "the model sees an error tool-result for its malformed spawn call");
    }
}
