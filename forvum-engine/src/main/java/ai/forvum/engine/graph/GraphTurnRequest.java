package ai.forvum.engine.graph;

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
 */
public record GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
        List<ToolSpec> belt, List<ChatMessage> messages, String outputSchema) {

    public GraphTurnRequest {
        belt = List.copyOf(belt);
        messages = List.copyOf(messages);
    }

    /** Free-text turn (no per-agent output schema) — the backward-compatible default. */
    public GraphTurnRequest(String sessionId, AgentId agentId, ChatModel model,
            List<ToolSpec> belt, List<ChatMessage> messages) {
        this(sessionId, agentId, model, belt, messages, null);
    }
}
