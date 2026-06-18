package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.MemoryTier;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.approval.ApprovalGate;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.routing.MemorySelector;
import ai.forvum.engine.session.compaction.Summarizer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

    private SupervisorGraph graphWith(InMemoryToolInvocationRecorder recorder, ApprovalGate gate,
            ToolProvider provider) {
        SupervisorGraph graph = new SupervisorGraph();
        graph.toolCallBridge = ToolTestFixtures.bridge(recorder, gate, provider);
        graph.workerRunner = workerRunner;
        graph.mapper = new ObjectMapper();
        return graph;
    }

    private static final ToolSpec SHELL_EXEC = new ToolSpec("shell.exec", "Run a shell command",
            PermissionScope.FS_WRITE,
            "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"]}",
            true);

    private static ToolProvider confirmProvider(String cannedResult) {
        return new AbstractToolProvider() {
            @Override
            public String extensionId() {
                return "fake-confirm";
            }

            @Override
            public List<ToolSpec> tools() {
                return List.of(SHELL_EXEC);
            }

            @Override
            public String invoke(String toolName, Map<String, Object> arguments) {
                return cannedResult;
            }
        };
    }

    private static AiMessage shellCall() {
        return AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("call-c").name("shell.exec").arguments("{\"cmd\":\"ls\"}").build()))
                .build();
    }

    @Test
    void confirmRequiredToolRunsWhenTheApprovalGateApproves() {
        // P2-14 #39: a confirm-required tool the model requests is parked through the gate; an approve lets
        // it run inside the turn and the result feeds back to the model exactly like a normal tool.
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ApprovalGate approve = (sessionId, agentId, tool, args) -> true;
        SupervisorGraph graph = graphWith(recorder, approve, confirmProvider("listing done"));

        ScriptedChatModel model = new ScriptedChatModel(shellCall(), AiMessage.from("Done: listing done"));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can run shell"),
                UserMessage.from("list the files"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(SHELL_EXEC), seed));

        assertEquals("Done: listing done", reply);
        assertEquals(1, recorder.invocations().size(), "the approved tool ran once");
        assertSame(InvocationStatus.OK, recorder.invocations().get(0).status(), "and audited ok");
        assertTrue(hasToolResult(model.seen.get(1), "listing done"),
                "the approved tool's result must feed back to the model");
    }

    @Test
    void confirmRequiredToolDeclinedByTheGateStillCompletesTheTurnAndIsAuditedDenied() {
        // A reject denies the call WITHOUT running it (audited denied) and feeds a clear "declined" result
        // back, so the model can explain — the turn still completes rather than aborting.
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        ApprovalGate reject = (sessionId, agentId, tool, args) -> false;
        SupervisorGraph graph = graphWith(recorder, reject, confirmProvider("must not run"));

        ScriptedChatModel model = new ScriptedChatModel(shellCall(),
                AiMessage.from("I could not run that — you declined it."));
        List<ChatMessage> seed = List.of(SystemMessage.from("you can run shell"),
                UserMessage.from("list the files"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(SHELL_EXEC), seed));

        assertEquals("I could not run that — you declined it.", reply, "the turn completes, it does not abort");
        assertEquals(1, recorder.invocations().size());
        assertSame(InvocationStatus.DENIED, recorder.invocations().get(0).status(), "the declined call is audited denied");
        assertTrue(hasToolResult(model.seen.get(1), "declined"),
                "the model must see a clear declined result, not a generic 'not permitted'");
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

    /** A schema requiring a string {@code answer} field; reused by the P2-12 structured-output tests. */
    private static final String ANSWER_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"answer\"],"
          + "\"properties\":{\"answer\":{\"type\":\"string\"}}}";

    @Test
    void validJsonReplyMatchingTheOutputSchemaIsDecodedAndReturned() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // A reply that parses as JSON and satisfies the schema (has the required string 'answer').
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("{\"answer\":\"42\"}"));
        List<ChatMessage> seed = List.of(SystemMessage.from("emit JSON"), UserMessage.from("the answer?"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                List.of(), seed, ANSWER_SCHEMA));

        // Surfaced as canonical JSON (re-serialized from the validated node).
        assertEquals("{\"answer\":\"42\"}", reply);
    }

    @Test
    void aReplyThatIsNotValidJsonFailsTheTurnNamingTheSchema() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("sorry, here is prose not JSON"));
        List<ChatMessage> seed = List.of(SystemMessage.from("emit JSON"), UserMessage.from("the answer?"));

        SupervisorGraphException thrown = assertThrows(SupervisorGraphException.class,
                () -> graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                        List.of(), seed, ANSWER_SCHEMA)),
                "a non-JSON reply under an outputSchema must abort the turn (no retry)");
        assertTrue(thrown.getMessage().contains(ANSWER_SCHEMA),
                "the error names the declared schema, message was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().toLowerCase().contains("not valid json"),
                "the error explains the reply is not valid JSON, message was: " + thrown.getMessage());
    }

    @Test
    void aReplyMissingARequiredFieldFailsTheTurnNamingTheField() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // Valid JSON object, but the schema-required 'answer' field is absent.
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("{\"other\":\"value\"}"));
        List<ChatMessage> seed = List.of(SystemMessage.from("emit JSON"), UserMessage.from("the answer?"));

        SupervisorGraphException thrown = assertThrows(SupervisorGraphException.class,
                () -> graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                        List.of(), seed, ANSWER_SCHEMA)),
                "a missing required field under an outputSchema must abort the turn (no retry)");
        assertTrue(thrown.getMessage().contains("answer"),
                "the error names the missing required field, message was: " + thrown.getMessage());
    }

    @Test
    void aReplyWithAWrongTypedFieldFailsTheTurnNamingTheField() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // 'answer' is present but a number, while the schema declares it a string.
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("{\"answer\":42}"));
        List<ChatMessage> seed = List.of(SystemMessage.from("emit JSON"), UserMessage.from("the answer?"));

        SupervisorGraphException thrown = assertThrows(SupervisorGraphException.class,
                () -> graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                        List.of(), seed, ANSWER_SCHEMA)));
        assertTrue(thrown.getMessage().contains("answer"),
                "the error names the mistyped field, message was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("string"),
                "the error names the declared type, message was: " + thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"just a string\"", "[1,2,3]"})
    void aBareJsonValueUnderAnObjectSchemaFailsTheTurnNamingTheRootType(String bareReply) {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // Valid JSON, but a bare scalar/array — not the object the schema's root 'type' declares; this
        // exercises the root-type check (the other structured tests only ever pass an object value).
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from(bareReply));
        List<ChatMessage> seed = List.of(SystemMessage.from("emit JSON"), UserMessage.from("the answer?"));

        SupervisorGraphException thrown = assertThrows(SupervisorGraphException.class,
                () -> graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                        List.of(), seed, ANSWER_SCHEMA)),
                "a bare JSON value under an object outputSchema must abort the turn (no retry)");
        assertTrue(thrown.getMessage().contains("(root)"),
                "the error names the root, message was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("declares it as object"),
                "the error names the declared root object type, message was: " + thrown.getMessage());
    }

    @Test
    void anAbsentOutputSchemaLeavesTheReplyAsFreeText() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));

        // The same prose that would FAIL validation passes through untouched when no schema is declared.
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("just some prose"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("hi"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed));

        assertEquals("just some prose", reply, "no schema = backward-compatible free text");
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

    // ---- Memory-retrieval wiring (commit 1): the Select pillar's read step ----

    /** A selector that always returns the given hit when consulted (overrides the public retrieve seam). */
    private static MemorySelector selectorReturning(MemoryHit hit) {
        return new MemorySelector() {
            @Override
            public List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy) {
                return List.of(hit);
            }
        };
    }

    private static int indexOfUserContaining(List<ChatMessage> messages, String needle) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage user && user.singleText().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void retrievedMemoryIsFramedAsDataAndInsertedBeforeTheUserQuestion() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));
        graph.memorySelector = selectorReturning(
                new MemoryHit(MemoryTier.SEMANTIC, "the user prefers metric units", 0.9, "mem-1"));

        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("Sure"));
        List<ChatMessage> seed = List.of(SystemMessage.from("be helpful"), UserMessage.from("how tall is it?"));

        String reply = graph.run(new GraphTurnRequest("s1", new AgentId("main"), model,
                List.of(), seed, null, MemoryPolicy.defaults()));

        assertEquals("Sure", reply);
        List<ChatMessage> seen = model.seen.get(0);
        int memIdx = indexOfUserContaining(seen, "<retrieved_memory>");
        int questionIdx = indexOfUserContaining(seen, "how tall is it?");
        assertTrue(memIdx >= 0, "the retrieved memory must be framed and reach the model's FIRST generate");
        assertTrue(((UserMessage) seen.get(memIdx)).singleText().contains("the user prefers metric units"),
                "the hit content rides inside the framed block");
        assertTrue(memIdx < questionIdx,
                "framed as DATA immediately before the user's question (never the system/instruction region)");
    }

    @Test
    void retrievalIsSkippedWhenTheStrategyIsNoneEvenWithAProvider() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));
        // The selector would always return a hit — so an appearing block would prove the graph DID consult
        // it; its absence proves the graph short-circuited on strategy NONE before any retrieval.
        graph.memorySelector = selectorReturning(
                new MemoryHit(MemoryTier.SEMANTIC, "should-not-appear", 1.0, "x"));

        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("ok"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("hello"));
        MemoryPolicy none = new MemoryPolicy(RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 8000);

        graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed, null, none));

        assertTrue(indexOfUserContaining(model.seen.get(0), "<retrieved_memory>") < 0,
                "strategy NONE must not retrieve, even with an installed provider");
        assertTrue(indexOfUserContaining(model.seen.get(0), "should-not-appear") < 0);
    }

    // ---- #56 proxy-model Compress pillar: retrieved memory + worker digests above the threshold ----

    private static String framedBlock(List<ChatMessage> seen) {
        return ((UserMessage) seen.get(indexOfUserContaining(seen, "<retrieved_memory>"))).singleText();
    }

    @Test
    void aRetrievedHitAboveTheCompressThresholdIsSummarizedBeforeFraming() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));
        String oversized = "x".repeat(50); // well above the 10-char threshold below
        graph.memorySelector = selectorReturning(new MemoryHit(MemoryTier.SEMANTIC, oversized, 0.9, "m1"));
        AtomicInteger summarizeCalls = new AtomicInteger();
        graph.summarizer = contents -> {
            summarizeCalls.incrementAndGet();
            return "PROXY_SUMMARY";
        };

        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("ok"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("a question?"));
        MemoryPolicy policy = new MemoryPolicy(RetrievalStrategy.HYBRID, EnumSet.allOf(MemoryTier.class), 8, 0.0, 10);

        graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed, null, policy));

        assertEquals(1, summarizeCalls.get(), "the oversized hit is summarized once through the proxy model");
        String block = framedBlock(model.seen.get(0));
        assertTrue(block.contains("PROXY_SUMMARY"), "the framed block carries the compressed summary");
        assertFalse(block.contains(oversized), "and not the raw oversized content");
    }

    @Test
    void aRetrievedHitBelowTheCompressThresholdIsNotSummarized() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));
        graph.memorySelector = selectorReturning(new MemoryHit(MemoryTier.SEMANTIC, "short fact", 0.9, "m1"));
        AtomicInteger summarizeCalls = new AtomicInteger();
        graph.summarizer = contents -> {
            summarizeCalls.incrementAndGet();
            return "NOPE";
        };

        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("ok"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("a question?"));
        MemoryPolicy policy = new MemoryPolicy(RetrievalStrategy.HYBRID, EnumSet.allOf(MemoryTier.class), 8, 0.0, 100);

        graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed, null, policy));

        assertEquals(0, summarizeCalls.get(), "a hit below the threshold is not compressed");
        assertTrue(framedBlock(model.seen.get(0)).contains("short fact"), "the raw content rides through uncompressed");
    }

    @Test
    void aWorkerDigestAboveTheThresholdIsCompressedInTheReduceNode() {
        InMemoryToolInvocationRecorder recorder = new InMemoryToolInvocationRecorder();
        SupervisorGraph graph = graphWith(recorder, readProvider("unused"));
        AtomicInteger summarizeCalls = new AtomicInteger();
        graph.summarizer = contents -> {
            summarizeCalls.incrementAndGet();
            return "DIGEST_SUMMARY";
        };

        AiMessage spawnCall = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("sp-1").name("spawn_worker")
                        .arguments("{\"childId\":\"researcher\",\"task\":\"find the long answer\"}").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(spawnCall, AiMessage.from("Final"));
        List<ChatMessage> seed = List.of(SystemMessage.from("sys"), UserMessage.from("delegate"));
        // NONE = no retrieval, but compressThresholdChars=5 still governs the reduce node (one shared knob).
        // The FakeWorkerRunner digest ("researcher result for: find the long answer") is far above 5.
        MemoryPolicy policy = new MemoryPolicy(RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 5);

        graph.run(new GraphTurnRequest("s1", new AgentId("main"), model, List.of(), seed, null, policy));

        assertEquals(1, summarizeCalls.get(), "the oversized worker digest is compressed once in reduce");
        assertTrue(hasToolResult(model.seen.get(1), "DIGEST_SUMMARY"),
                "the model sees the compressed digest fed back, not the raw worker output");
        assertFalse(hasToolResult(model.seen.get(1), "researcher result for"),
                "the raw oversized digest does not reach the model");
    }
}
