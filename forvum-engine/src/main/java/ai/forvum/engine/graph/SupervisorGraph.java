package ai.forvum.engine.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.routing.MemorySelector;
import ai.forvum.engine.routing.RetrievedMemory;
import ai.forvum.engine.session.compaction.Summarizer;
import ai.forvum.engine.tools.ApprovalDeniedException;
import ai.forvum.engine.tools.PermissionDeniedException;
import ai.forvum.engine.tools.ToolCallBridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Orchestrator-Workers supervisor (ULTRAPLAN section 5.5) materialized as a LangGraph4j
 * {@link StateGraph}. This is where tool execution and sub-agent fan-out finally enter the turn (M13/M14
 * built the tool substrate; M7 built spawn). Flow:
 *
 * <pre>
 *   START → route → generate
 *   generate ──(tool calls)──→ tool_loop ──→ generate          (the ReAct tool loop)
 *   generate ──(spawn calls)─→ spawn_worker → worker_run → reduce → generate
 *   generate ──(no calls)────→ END                             (final answer)
 * </pre>
 *
 * <p>The model is offered, alongside the agent's belt, a built-in {@code spawn_worker} tool; when it calls
 * it, the graph materializes worker sub-agents ({@code spawn_worker}), drives them <strong>in parallel on
 * virtual threads</strong> ({@code worker_run}, section 3.8 — no {@code StructuredTaskScope}), and merges
 * each isolated worker's digest back as a tool result ({@code reduce}, the Isolate/Compress boundary —
 * only the digest crosses, never the worker's raw window). Tool execution is always permission-gated +
 * audited via {@link ToolCallBridge}/{@code ToolExecutor}.
 *
 * <p>Reconciliation R6: {@link GraphState} holds only serialization-safe {@code String}s; the
 * {@link ChatMessage} conversation lives in a per-turn mutable {@link Turn} holder captured by the node
 * lambdas (the graph is compiled per turn), so no langchain4j type is ever serialized (native-clean).
 *
 * <p>v0.1 scope: {@code route} is a deterministic direct-path classifier; a worker runs a single
 * generation (no nested tool loop or sub-spawn) via {@link WorkerRunner}; {@code reduce} compresses each
 * worker digest exceeding {@code memoryPolicy.compressThresholdChars} through the proxy-model
 * {@link Summarizer} (#56 — the same knob that compresses retrieved memory before it enters the window).
 */
@ApplicationScoped
public class SupervisorGraph {

    /** Safety cap on {@code generate ⇄ (tool_loop|workers)} rounds, independent of any per-agent budget. */
    private static final int MAX_ROUNDS = 8;

    /** The engine-handled tool the model calls to delegate a subtask to a worker sub-agent. */
    static final String SPAWN_TOOL = "spawn_worker";

    private static final ToolSpecification SPAWN_SPEC = ToolSpecification.builder()
            .name(SPAWN_TOOL)
            .description("Delegate a subtask to a specialized worker sub-agent that runs in isolation; "
                    + "returns the worker's result. Use when a focused subtask benefits from its own context.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("childId", "a short, distinct id for the worker sub-agent")
                    .addStringProperty("task", "the self-contained subtask to delegate")
                    .required("childId", "task")
                    .build())
            .build();

    private static final TypeReference<Map<String, Object>> ARGS = new TypeReference<Map<String, Object>>() {};

    @Inject
    ToolCallBridge toolCallBridge;

    @Inject
    WorkerRunner workerRunner;

    @Inject
    MemorySelector memorySelector;

    @Inject
    Summarizer summarizer;

    @Inject
    ObjectMapper mapper;

    /** Run one turn through the compiled graph, returning the final assistant text. */
    @WithSpan("forvum.graph.run")
    public String run(GraphTurnRequest request) {
        // §3.6 baseline: name the supervisor-graph span + mark its carrier (no-op when the SDK is disabled).
        Span.current()
                .setAttribute("forvum.session.id", request.sessionId())
                .setAttribute("forvum.agent.id", request.agentId().value())
                .setAttribute("thread.is_virtual", Thread.currentThread().isVirtual());
        Turn turn = new Turn(request, retrieveAndFrame(request),
                toolCallBridge.specificationsFor(request.belt()));
        String finalText;
        try {
            CompiledGraph<GraphState> graph = compile(turn);
            Optional<GraphState> result = graph.invoke(Map.of());
            finalText = result.flatMap(GraphState::finalText).orElseGet(turn::lastAssistantText);
        } catch (SupervisorGraphException e) {
            throw e;
        } catch (Exception e) {
            throw new SupervisorGraphException("Supervisor graph failed for session "
                    + request.sessionId(), e);
        }
        return enforceOutputSchema(request, finalText);
    }

    /**
     * The Context-Engineering Select pillar's read step (DR-5): retrieve memory relevant to the turn's
     * user message ONCE at turn entry (not per generate round) and frame it as a {@code <retrieved_memory>}
     * DATA block inserted just before the user's question (DR-6a §9 — never spliced into the
     * system/instruction region). Returns the seeded messages unchanged — retrieval disabled — when the
     * policy is null / {@code NONE}, no selector/provider is available, the session or query text is blank,
     * or retrieval yields nothing. The returned list is always a fresh mutable copy ({@link Turn} mutates
     * it across rounds).
     */
    private List<ChatMessage> retrieveAndFrame(GraphTurnRequest request) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        if (ReplayContext.CURRENT_REPLAY.isBound()) {
            return messages; // replay (#57): deterministic — never re-retrieve memory
        }
        MemoryPolicy policy = request.memoryPolicy();
        if (policy == null || policy.strategy() == RetrievalStrategy.NONE || memorySelector == null) {
            return messages;
        }
        int lastUser = lastUserIndex(messages);
        if (lastUser < 0) {
            return messages;
        }
        String queryText = userText((UserMessage) messages.get(lastUser));
        String sessionId = request.sessionId();
        if (queryText == null || queryText.isBlank() || sessionId == null || sessionId.isBlank()) {
            return messages;
        }
        List<MemoryHit> hits = memorySelector.retrieve(
                new MemoryQuery(request.agentId().value(), sessionId, queryText), policy);
        String block = RetrievedMemory.frame(compressHits(hits, policy.compressThresholdChars()));
        if (block != null) {
            // Insert as a user-role DATA message immediately before the user's question (context → question).
            messages.add(lastUser, UserMessage.from(block));
        }
        return messages;
    }

    /**
     * The Context-Engineering Compress pillar (#56): summarize any retrieved hit whose content exceeds
     * {@code threshold} chars through the small-and-fast proxy model ({@link Summarizer}) before it
     * re-enters the window, leaving shorter hits untouched. {@code threshold <= 0} disables compression.
     */
    private List<MemoryHit> compressHits(List<MemoryHit> hits, int threshold) {
        if (threshold <= 0 || hits.isEmpty()) {
            return hits;
        }
        List<MemoryHit> out = new ArrayList<>(hits.size());
        for (MemoryHit hit : hits) {
            String content = hit.content();
            if (content != null && content.length() > threshold) {
                out.add(new MemoryHit(hit.tier(), summarizer.summarize(List.of(content)),
                        hit.score(), hit.source()));
            } else {
                out.add(hit);
            }
        }
        return out;
    }

    /** Index of the last {@link UserMessage} in {@code messages}, or {@code -1} if there is none. */
    private static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    /** The user message's text, or {@code null} for a multi-part/non-text message (skip retrieval, don't fail). */
    private static String userText(UserMessage message) {
        try {
            return message.singleText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * When the turn declares a per-agent {@code outputSchema} (P2-12), the final reply must parse as JSON
     * and validate against it; otherwise the raw text passes through unchanged. A validation failure is a
     * terminal turn error (no retry): it is wrapped in a {@link SupervisorGraphException} that NAMES the
     * schema and the failure, so {@code TurnService} renders it as an {@code ErrorEvent}. On success the
     * reply is re-serialized from the validated {@code JsonNode} so the channel always sees canonical JSON.
     */
    private String enforceOutputSchema(GraphTurnRequest request, String finalText) {
        String schema = request.outputSchema();
        if (schema == null) {
            return finalText;
        }
        try {
            return mapper.writeValueAsString(new OutputSchemaValidator(mapper).validate(schema, finalText));
        } catch (OutputSchemaException e) {
            throw new SupervisorGraphException(
                "Output-schema validation failed for session " + request.sessionId()
              + " against schema " + schema + ": " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new SupervisorGraphException(
                "Output-schema validation failed for session " + request.sessionId()
              + ": the validated reply could not be re-serialized.", e);
        }
    }

    private CompiledGraph<GraphState> compile(Turn turn) throws Exception {
        return new StateGraph<>(GraphState.SCHEMA, GraphState::new)
                .addNode("route", node_async(state -> route(state, turn)))
                .addNode("generate", node_async(state -> generate(state, turn)))
                .addNode("tool_loop", node_async(state -> toolLoop(state, turn)))
                .addNode("spawn_worker", node_async(state -> spawnWorker(state, turn)))
                .addNode("worker_run", node_async(state -> workerRun(state, turn)))
                .addNode("reduce", node_async(state -> reduce(state, turn)))
                .addEdge(START, "route")
                .addEdge("route", "generate")
                .addConditionalEdges("generate", edge_async(state -> state.next().orElse("done")),
                        Map.of("tools", "tool_loop", "spawn", "spawn_worker", "done", END))
                .addEdge("tool_loop", "generate")
                .addEdge("spawn_worker", "worker_run")
                .addEdge("worker_run", "reduce")
                .addEdge("reduce", "generate")
                // LangGraph4j counts every NODE execution against recursionLimit (default 25); a spawn
                // round is 4 nodes, so raise it past MAX_ROUNDS' worst case so the in-graph MAX_ROUNDS
                // guard binds (graceful done) instead of the framework throwing mid-turn.
                .compile(CompileConfig.builder().recursionLimit(MAX_ROUNDS * 4 + 8).build());
    }

    private Map<String, Object> route(GraphState state, Turn turn) {
        return Map.of(GraphState.ROUTE, "generate");
    }

    private Map<String, Object> generate(GraphState state, Turn turn) {
        if (turn.round++ >= MAX_ROUNDS) {
            return Map.of(GraphState.NEXT, "done", GraphState.FINAL, turn.lastAssistantText());
        }
        List<ToolSpecification> offered = new ArrayList<>(turn.toolSpecs);
        offered.add(SPAWN_SPEC);
        AiMessage reply = turn.model.chat(ChatRequest.builder()
                .messages(turn.conversation)
                .toolSpecifications(offered)
                .build()).aiMessage();
        turn.conversation.add(reply);

        List<ToolExecutionRequest> requests = reply.toolExecutionRequests();
        if (requests.stream().anyMatch(request -> SPAWN_TOOL.equals(request.name()))) {
            return Map.of(GraphState.NEXT, "spawn");
        }
        if (!requests.isEmpty()) {
            return Map.of(GraphState.NEXT, "tools");
        }
        return Map.of(GraphState.NEXT, "done", GraphState.FINAL, reply.text() == null ? "" : reply.text());
    }

    private Map<String, Object> toolLoop(GraphState state, Turn turn) {
        AiMessage reply = (AiMessage) turn.conversation.get(turn.conversation.size() - 1);
        for (ToolExecutionRequest request : reply.toolExecutionRequests()) {
            turn.conversation.add(ToolExecutionResultMessage.from(request, runTool(turn, request)));
        }
        return Map.of();
    }

    private String runTool(Turn turn, ToolExecutionRequest request) {
        if (ReplayContext.CURRENT_REPLAY.isBound()) {
            // Replay (#57): serve the recorded result FIFO-per-tool — never execute or audit a real tool,
            // so a substituted-model rerun sees the SAME outputs (deterministic). A miss → synthetic marker.
            return ReplayContext.CURRENT_REPLAY.get().next(request.name());
        }
        try {
            return toolCallBridge.dispatch(turn.sessionId, turn.agentId, turn.belt,
                    request.name(), request.arguments());
        } catch (ApprovalDeniedException declined) {
            // P2-14 #39: the tool was permitted (belt + scope) but the owner declined/timed out the
            // confirmation. Feed a clear, distinct result back so the model explains it was declined — the
            // turn still completes (this is caught BEFORE the PermissionDeniedException arm it extends).
            return "Tool '" + request.name() + "' was not run: the user declined the confirmation request.";
        } catch (PermissionDeniedException denied) {
            return "Tool '" + request.name() + "' is not permitted for this agent.";
        } catch (RuntimeException failure) {
            return "Tool '" + request.name() + "' failed: " + failure.getMessage();
        }
    }

    private Map<String, Object> spawnWorker(GraphState state, Turn turn) {
        AiMessage reply = (AiMessage) turn.conversation.get(turn.conversation.size() - 1);
        for (ToolExecutionRequest request : reply.toolExecutionRequests()) {
            if (SPAWN_TOOL.equals(request.name())) {
                // Materialize the worker; on a malformed/failed spawn surface a model-visible error so the
                // request still gets a result (and the turn stays well-formed) instead of failing.
                String error = prepareSpawn(turn, request);
                if (error != null) {
                    turn.conversation.add(ToolExecutionResultMessage.from(request, error));
                }
            } else {
                // A belt tool emitted in the SAME reply as spawn_worker is still gated + audited (R3) and
                // answered, so no ToolExecutionRequest is left without a result.
                turn.conversation.add(ToolExecutionResultMessage.from(request, runTool(turn, request)));
            }
        }
        return Map.of();
    }

    /**
     * Validate + materialize one {@code spawn_worker} request, recording it into {@link Turn#spawns} on
     * success. Returns {@code null} on success, or a model-visible error string (malformed JSON, missing
     * args, spawn collision/self-id) to surface as the tool result without aborting the turn.
     */
    private String prepareSpawn(Turn turn, ToolExecutionRequest request) {
        Map<String, Object> args;
        try {
            args = mapper.readValue(request.arguments(), ARGS);
        } catch (JsonProcessingException e) {
            return "spawn_worker arguments are not valid JSON: " + request.arguments();
        }
        Object childId = args.get("childId");
        Object task = args.get("task");
        if (childId == null || task == null) {
            return "spawn_worker requires both 'childId' and 'task'.";
        }
        AgentId child;
        try {
            child = new AgentId(childId.toString());
            workerRunner.spawn(turn.agentId, child, List.of());
        } catch (RuntimeException e) {
            return "Could not spawn worker: " + e.getMessage();
        }
        turn.spawns.add(new SpawnRequest(request, child, task.toString()));
        return null;
    }

    private Map<String, Object> workerRun(GraphState state, Turn turn) {
        // Capture the turn's OTel context so each worker's spans (its forvum.llm.call) nest under the turn
        // trace rather than orphaning — OTel context is thread-local and does NOT cross the VT fan-out
        // (the same reason CURRENT_AGENT is re-bound inside the worker). A no-op when the SDK is disabled.
        Context parent = Context.current();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(turn.spawns.size());
            for (SpawnRequest spawn : turn.spawns) {
                Runnable task = () -> turn.digests.put(spawn.request().id(),
                        workerRunner.runWorker(spawn.childId(), spawn.task(), turn.sessionId));
                futures.add(executor.submit(parent.wrap(task)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupervisorGraphException("Interrupted while running worker sub-agents", e);
        } catch (ExecutionException e) {
            throw new SupervisorGraphException("A worker sub-agent failed", e.getCause());
        }
        return Map.of();
    }

    private Map<String, Object> reduce(GraphState state, Turn turn) {
        List<String> digests = new ArrayList<>(turn.spawns.size());
        for (SpawnRequest spawn : turn.spawns) {
            String digest = turn.digests.getOrDefault(spawn.request().id(), "");
            digests.add(digest);
            turn.conversation.add(ToolExecutionResultMessage.from(spawn.request(),
                    compress(digest, turn.compressThreshold)));
        }
        turn.spawns.clear();
        turn.digests.clear();
        return digests.isEmpty() ? Map.of() : Map.of(GraphState.WORKER_DIGESTS, digests);
    }

    /**
     * The Compress pillar at the worker-digest merge (#56, the §5.5 reduce boundary): summarize a digest
     * exceeding {@code threshold} chars through the proxy-model {@link Summarizer} ({@code qwen3:1.7b}),
     * else pass it through. The SAME {@code compressThresholdChars} knob governs this and retrieved-memory
     * compression (DR-5); {@code threshold <= 0} disables it (the pre-#56 pass-through behavior).
     */
    private String compress(String digest, int threshold) {
        if (threshold <= 0 || digest == null || digest.length() <= threshold) {
            return digest;
        }
        return summarizer.summarize(List.of(digest));
    }

    /** One delegated subtask: the model's {@code spawn_worker} call paired with its parsed child + task. */
    private record SpawnRequest(ToolExecutionRequest request, AgentId childId, String task) {
    }

    /** Per-turn mutable holder captured by the node lambdas (R6 — keeps langchain4j types out of state). */
    private static final class Turn {
        private final String sessionId;
        private final AgentId agentId;
        private final ChatModel model;
        private final List<ToolSpec> belt;
        private final List<ToolSpecification> toolSpecs;
        private final List<ChatMessage> conversation;
        private final int compressThreshold;
        private final List<SpawnRequest> spawns = new ArrayList<>();
        private final ConcurrentMap<String, String> digests = new ConcurrentHashMap<>();
        private int round;

        private Turn(GraphTurnRequest request, List<ChatMessage> conversation,
                List<ToolSpecification> toolSpecs) {
            this.sessionId = request.sessionId();
            this.agentId = request.agentId();
            this.model = request.model();
            this.belt = request.belt();
            this.toolSpecs = toolSpecs;
            // Already a fresh mutable copy (built by retrieveAndFrame), mutated across rounds.
            this.conversation = conversation;
            // The §5.5 reduce node compresses worker digests above this; 0 disables it (no memory policy,
            // or a replay #57 where compression must be off for determinism).
            this.compressThreshold = ReplayContext.CURRENT_REPLAY.isBound() ? 0
                    : (request.memoryPolicy() != null ? request.memoryPolicy().compressThresholdChars() : 0);
        }

        private String lastAssistantText() {
            for (int i = conversation.size() - 1; i >= 0; i--) {
                if (conversation.get(i) instanceof AiMessage reply && reply.text() != null) {
                    return reply.text();
                }
            }
            return "";
        }
    }
}
