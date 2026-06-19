package ai.forvum.engine.graph;

import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * The inputs to one supervisor-graph turn (ULTRAPLAN section 5.5). The caller ({@code Agent.respond})
 * resolves the per-turn collaborators — the {@link ChatModel} via {@code LlmSelector}, the {@code belt}
 * via {@code AgentToolBelt}, and the seeded {@code messages} (system prompt + history + the user message)
 * — and hands them to {@link SupervisorGraph#run}. Keeping the model and belt as parameters (not injected
 * into the graph) decouples the graph from routing/registry and makes it testable with a scripted model.
 *
 * @param sessionId    the conversation/session id (for tool-call auditing)
 * @param agentId      the bound agent (for tool-call auditing + belt attribution)
 * @param model        the resolved chat model for this turn (already fallback-wrapped)
 * @param belt         the agent's permitted tools (the permission boundary the tool_loop enforces)
 * @param messages     the seeded conversation: system prompt + committed history + the new user message
 * @param outputSchema the persona's optional JSON-Schema string (P2-12); {@code null} keeps free-text
 *                     output, non-null forces the final reply to parse + validate against it
 * @param memoryPolicy the persona's retrieval policy (DR-5 / DR-8); {@code null} or {@code strategy=NONE}
 *                     disables retrieval, otherwise the graph retrieves and frames {@code
 *                     <retrieved_memory>} data once at turn entry (DR-6a §9, the Select pillar)
 * @param cycle        the agent's declared reflection cycle (DR-8 DP-7, #51); {@code null} runs the
 *                     standard supervisor graph, non-null compiles a cyclic generation graph instead
 */
public record GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
        List<ToolSpec> belt, List<ChatMessage> messages, String outputSchema, MemoryPolicy memoryPolicy,
        CycleSpec cycle) {

    public GraphTurnRequest {
        belt = List.copyOf(belt);
        messages = List.copyOf(messages);
    }

    /** Turn with retrieval policy but no declared cycle — the standard supervisor graph. */
    public GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
            List<ToolSpec> belt, List<ChatMessage> messages, String outputSchema, MemoryPolicy memoryPolicy) {
        this(sessionId, agentId, model, belt, messages, outputSchema, memoryPolicy, null);
    }

    /** Turn with an output schema but no retrieval policy and no declared cycle. */
    public GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
            List<ToolSpec> belt, List<ChatMessage> messages, String outputSchema) {
        this(sessionId, agentId, model, belt, messages, outputSchema, null, null);
    }

    /** Free-text turn (no output schema, no retrieval policy, no cycle) — the backward-compatible default. */
    public GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
            List<ToolSpec> belt, List<ChatMessage> messages) {
        this(sessionId, agentId, model, belt, messages, null, null, null);
    }
}
