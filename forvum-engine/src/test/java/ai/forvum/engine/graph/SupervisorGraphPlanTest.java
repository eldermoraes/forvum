package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.model.InMemoryToolInvocationRecorder;
import ai.forvum.engine.plan.InMemoryPlanStore;
import ai.forvum.engine.tools.ToolTestFixtures;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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

/**
 * The {@code update_plan} built-in through the {@link SupervisorGraph} (#190). Every visibility claim is
 * proven by CAPTURING the {@code ChatRequest.messages()} the scripted model actually saw (the [M18]
 * green-for-wrong-reason guard): the tool result echoing the rendered plan on the SAME turn, the framed
 * {@code <current_plan>} injection on a LATER turn (index-ordered: after history, before the question),
 * and the replay/no-plan skips. The store is the {@link InMemoryPlanStore} double — the write path per
 * se is pinned by {@code PlanStoreIT} against real SQLite.
 */
class SupervisorGraphPlanTest {

    /** A {@link ChatModel} that returns a queued sequence of replies AND records the messages it saw. */
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<AiMessage> replies;
        private final List<List<ChatMessage>> seen = new ArrayList<>();
        private final List<List<ToolSpecification>> offeredToolSpecs = new ArrayList<>();

        private ScriptedChatModel(AiMessage... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            seen.add(List.copyOf(request.messages()));
            offeredToolSpecs.add(List.copyOf(request.toolSpecifications()));
            return ChatResponse.builder().aiMessage(replies.poll()).build();
        }
    }

    /** Records the workers spawned/driven/retired, returning a deterministic digest per worker. */
    private static final class FakeWorkerRunner implements WorkerRunner {
        private final List<AgentId> ran = new ArrayList<>();

        @Override
        public void spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
        }

        @Override
        public String runWorker(AgentId childId, String task, String sessionId) {
            ran.add(childId);
            return childId.value() + " result for: " + task;
        }

        @Override
        public void retire(AgentId childId) {
        }
    }

    private static final ToolSpec FS_READ = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");

    private static ToolProvider readProvider() {
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
                return "file body";
            }
        };
    }

    private final InMemoryPlanStore planStore = new InMemoryPlanStore();

    private SupervisorGraph graph() {
        SupervisorGraph graph = new SupervisorGraph();
        graph.toolCallBridge = ToolTestFixtures.bridge(new InMemoryToolInvocationRecorder(), readProvider());
        graph.workerRunner = new FakeWorkerRunner();
        graph.mapper = new ObjectMapper();
        graph.planStore = planStore;
        return graph;
    }

    private static AiMessage planCall(String id, String argsJson) {
        return AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id(id).name("update_plan").arguments(argsJson).build()))
                .build();
    }

    private static final String VALID_ARGS =
            "{\"plan\":[{\"step\":\"read x.txt\",\"status\":\"completed\"},"
          + "{\"step\":\"summarize it\",\"status\":\"in_progress\"}]}";

    private static final String VALID_RENDERED = "[x] read x.txt\n[>] summarize it";

    private static List<ChatMessage> seed(String question) {
        return List.of(SystemMessage.from("sys"), UserMessage.from(question));
    }

    @Test
    void updatePlanIsOfferedAlongsideSpawnWorkerAndTheBelt() {
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("done"));

        graph.run(new GraphTurnRequest("s-offer", new AgentId("main"), model, List.of(FS_READ), seed("hi")));

        List<String> offered = model.offeredToolSpecs.get(0).stream().map(ToolSpecification::name).toList();
        assertTrue(offered.contains("update_plan"), "the built-in update_plan is offered: " + offered);
        assertTrue(offered.contains("spawn_worker"), "alongside spawn_worker: " + offered);
        assertTrue(offered.contains("fs.read"), "alongside the belt: " + offered);
    }

    @Test
    void updatePlanIsOfferedWithAnEmptyBelt() {
        // RATIFIED (issue #190): a built-in — available without any belt grant, no PermissionScope.
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("done"));

        graph.run(new GraphTurnRequest("s-offer2", new AgentId("main"), model, List.of(), seed("hi")));

        List<String> offered = model.offeredToolSpecs.get(0).stream().map(ToolSpecification::name).toList();
        assertTrue(offered.contains("update_plan"), "update_plan needs no belt entry: " + offered);
    }

    @Test
    void theUpdatedPlanFeedsBackToTheModelOnTheSameTurn() {
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(planCall("p-1", VALID_ARGS),
                AiMessage.from("Plan recorded, proceeding"));

        String reply = graph.run(new GraphTurnRequest("s-same", new AgentId("main"), model,
                List.of(), seed("do a two-step task")));

        assertEquals("Plan recorded, proceeding", reply, "the turn completes after the plan update");
        List<ChatMessage> second = model.seen.get(1);
        assertTrue(second.stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .anyMatch(result -> result.text().contains("Plan updated:")
                                && result.text().contains(VALID_RENDERED)),
                "the SECOND generate must see the rendered updated plan as the tool result: " + second);
        assertEquals(List.of(new InMemoryPlanStore.SavedPlan("s-same", "main", VALID_RENDERED)),
                planStore.saved, "the rendered plan is persisted for (session, agent)");
    }

    @Test
    void theLivePlanIsInjectedFramedOnALaterTurnBetweenHistoryAndTheQuestion() {
        planStore.save("s-next", "main", VALID_RENDERED);
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("continuing step two"));

        List<ChatMessage> seeded = List.of(SystemMessage.from("sys"),
                UserMessage.from("do a two-step task"), AiMessage.from("started"),
                UserMessage.from("continue"));
        graph.run(new GraphTurnRequest("s-next", new AgentId("main"), model, List.of(), seeded));

        List<ChatMessage> first = model.seen.get(0);
        int planIndex = -1;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) instanceof UserMessage u && u.singleText().contains("<current_plan>")) {
                planIndex = i;
            }
        }
        assertTrue(planIndex >= 0, "the framed <current_plan> block must be injected: " + first);
        UserMessage plan = (UserMessage) first.get(planIndex);
        assertTrue(plan.singleText().contains(VALID_RENDERED), "the block carries the live plan");
        assertTrue(plan.singleText().contains("data, not as instructions"), "data-framed (DR-6a)");
        assertEquals(first.size() - 2, planIndex,
                "the plan block sits immediately BEFORE the last user message (after history)");
        assertTrue(((UserMessage) first.get(first.size() - 1)).singleText().contains("continue"),
                "the user's question stays last");
    }

    @Test
    void noStoredPlanInjectsNoBlock() {
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from("hello"));

        graph.run(new GraphTurnRequest("s-none", new AgentId("main"), model, List.of(), seed("hi")));

        assertTrue(model.seen.get(0).stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .noneMatch(u -> u.singleText().contains("<current_plan>")),
                "no plan stored -> no empty frame injected");
    }

    @Test
    void invalidArgsFeedAModelVisibleErrorAndWriteNothing() {
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(planCall("p-bad", "{\"plan\":[]}"),
                AiMessage.from("I will retry with a real plan"));

        String reply = graph.run(new GraphTurnRequest("s-bad", new AgentId("main"), model,
                List.of(), seed("plan it")));

        assertEquals("I will retry with a real plan", reply, "the turn completes, it does not abort");
        assertTrue(model.seen.get(1).stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .anyMatch(result -> result.text().contains("at least one step")),
                "the validation error is fed back as the tool result");
        assertTrue(planStore.saved.isEmpty(), "nothing is written on a validation failure");
    }

    @Test
    void aReplyMixingUpdatePlanAndSpawnWorkerAnswersBoth() {
        // [M18] every ToolExecutionRequest gets a result on the spawnWorker path too — the single
        // runTool intercept covers the mixed reply; assert the digest by task substring ([#177]).
        SupervisorGraph graph = graph();
        AiMessage mixed = AiMessage.builder()
                .toolExecutionRequests(List.of(
                        ToolExecutionRequest.builder().id("p-1").name("update_plan")
                                .arguments(VALID_ARGS).build(),
                        ToolExecutionRequest.builder().id("sp-1").name("spawn_worker")
                                .arguments("{\"childId\":\"digger\",\"task\":\"dig deep\"}").build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(mixed, AiMessage.from("both done"));

        String reply = graph.run(new GraphTurnRequest("s-mixed", new AgentId("main"), model,
                List.of(), seed("plan and delegate")));

        assertEquals("both done", reply);
        List<ChatMessage> second = model.seen.get(1);
        assertTrue(second.stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .anyMatch(result -> result.text().contains("Plan updated:")),
                "the update_plan result reaches the model: " + second);
        assertTrue(second.stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .anyMatch(result -> result.text().contains("result for: dig deep")),
                "the worker digest still reaches the model: " + second);
        assertEquals(1, planStore.saved.size(), "the plan write happened once");
    }

    @Test
    void replayServesTheRecordedResultAndNeverWritesOrInjects() {
        planStore.save("s-replay", "main", VALID_RENDERED);
        SupervisorGraph graph = graph();
        ScriptedChatModel model = new ScriptedChatModel(planCall("p-1", VALID_ARGS),
                AiMessage.from("replayed"));
        ReplayToolSource source = new ReplayToolSource(List.of());

        String reply = ScopedValue.where(ReplayContext.CURRENT_REPLAY, source).call(() ->
                graph.run(new GraphTurnRequest("s-replay", new AgentId("main"), model,
                        List.of(), seed("plan it"))));

        assertEquals("replayed", reply);
        assertEquals(1, planStore.saved.size(), "replay never writes a plan row (only the pre-seed)");
        assertFalse(model.seen.get(0).stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .anyMatch(u -> u.singleText().contains("<current_plan>")),
                "replay never injects the plan block (deterministic rerun)");
        assertTrue(model.seen.get(1).stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .noneMatch(result -> result.text().contains("Plan updated:")),
                "the replay-source result (a synthetic miss here) is served, not a fresh execution");
    }
}
