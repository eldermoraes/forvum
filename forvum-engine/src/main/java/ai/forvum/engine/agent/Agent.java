package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.routing.LlmSelector;

import jakarta.inject.Inject;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code @AgentScoped} facade for a live agent: it aggregates the agent's {@link Persona}, its
 * {@link AgentToolBelt} and {@link AgentMemory}, and runs a turn. Isolated per
 * {@link CurrentAgent#CURRENT_AGENT}, so two agents bound on two virtual threads resolve distinct
 * instances and one agent always resolves the same cached instance (ULTRAPLAN section 5.1).
 */
@AgentScoped
public class Agent {

    @Inject
    AgentRegistry registry;

    @Inject
    AgentToolBelt toolBelt;

    @Inject
    AgentMemory memory;

    @Inject
    SessionManager sessions;

    @Inject
    LlmSelector llmSelector;

    /** This agent's persona (system prompt + structural spec), for the currently bound agent. */
    public Persona persona() {
        return registry.persona(CurrentAgent.CURRENT_AGENT.get());
    }

    /** The agent's allowed-tool glob belt. */
    public AgentToolBelt toolBelt() {
        return toolBelt;
    }

    /** The agent's three-tier memory. */
    public AgentMemory memory() {
        return memory;
    }

    /**
     * Run one turn: persist the user message, call the agent's model over the system prompt + the
     * conversational history, persist the assistant reply and a turn observation, and return the reply.
     * A deliberate single-shot path — routing, the tool loop, and sub-agent fan-out arrive with the
     * LangGraph4j {@code SupervisorGraph} (M18).
     */
    public String respond(String sessionId, String userText) {
        AgentId id = CurrentAgent.CURRENT_AGENT.get();
        sessions.ensureSession(sessionId, id);
        memory.addUserMessage(sessionId, userText);

        Persona persona = registry.persona(id);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(persona.systemPrompt()));
        messages.addAll(memory.messages(sessionId));

        ChatModel model = llmSelector.select(persona, sessionId);
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
        String reply = response.aiMessage().text();

        memory.addAssistantMessage(sessionId, reply);
        memory.recordObservation(sessionId, "turn completed");
        return reply;
    }

    /** Identity of the resolved per-agent instance — lets tests assert per-agent isolation/caching. */
    public int identity() {
        return System.identityHashCode(this);
    }
}
