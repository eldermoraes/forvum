package ai.forvum.engine.graph;

import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.routing.LlmSelector;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * The production {@link WorkerRunner} (ULTRAPLAN section 5.5). {@link #spawn} delegates to
 * {@code AgentRegistry.spawn}; {@link #runWorker} binds the child's {@code @AgentScoped} context via
 * {@link CurrentAgent#CURRENT_AGENT} (re-bound inside the calling virtual thread, since {@code ScopedValue}
 * does not inherit across threads) and runs a single direct generation for the worker. v0.1 worker scope:
 * one model call over the child's system prompt + the task (no nested tool loop or sub-spawn — those are a
 * documented v1+ refinement); the worker's isolated window never crosses back raw, only its digest.
 */
@ApplicationScoped
public class DefaultWorkerRunner implements WorkerRunner {

    @Inject
    AgentRegistry registry;

    @Inject
    LlmSelector llmSelector;

    @Override
    public void spawn(AgentId parentId, AgentId childId, List<String> allowedTools) {
        registry.spawn(parentId, childId, allowedTools);
    }

    @Override
    public String runWorker(AgentId childId, String task, String sessionId) {
        return ScopedValue.where(CurrentAgent.CURRENT_AGENT, childId).call(() -> {
            Persona persona = registry.persona(childId);
            ChatModel model = llmSelector.select(persona, sessionId);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(persona.systemPrompt()),
                    UserMessage.from(task));
            String reply = model.chat(ChatRequest.builder().messages(messages).build()).aiMessage().text();
            return reply == null ? "" : reply;
        });
    }
}
