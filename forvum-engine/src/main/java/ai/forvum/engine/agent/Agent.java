package ai.forvum.engine.agent;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.Persona;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.graph.GraphTurnRequest;
import ai.forvum.engine.graph.SupervisorGraph;
import ai.forvum.engine.persistence.CaprRecorder;
import ai.forvum.engine.routing.LlmSelector;

import jakarta.inject.Inject;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

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

    @Inject
    SupervisorGraph supervisorGraph;

    @Inject
    CaprRecorder caprRecorder;

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
     * Run one turn through the LangGraph4j {@link SupervisorGraph} (M18): build the request (system prompt
     * + committed history + the new user message), resolve the agent's model and tool belt, and drive the
     * supervisor graph — which routes, runs the permission-gated tool loop, and fans sub-agents out on
     * virtual threads. On success the user message, assistant reply, and a turn observation are persisted
     * atomically, then a {@code capr_events} verdict is recorded for the turn. The conversational tier is
     * written only <em>after</em> a successful turn, so a failed turn leaves no orphan rows (the failed
     * attempt is still ledgered in {@code provider_calls} by the fallback decorator).
     */
    public String respond(String sessionId, String userText) {
        return respond(sessionId, userText, null);
    }

    /**
     * As {@link #respond(String, String)}, but using {@code modelOverride} instead of the agent's persona
     * model when non-null — the M19 cron path runs a turn with the cron's own model (distinct from the
     * agent's default). All other behavior (session, memory, graph, CAPR) is identical.
     */
    public String respond(String sessionId, String userText, ChatModel modelOverride) {
        AgentId id = CurrentAgent.CURRENT_AGENT.get();
        sessions.ensureSession(sessionId, id);

        Persona persona = registry.persona(id);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(persona.systemPrompt()));
        messages.addAll(memory.messages(sessionId));   // committed prior history
        messages.add(UserMessage.from(userText));        // this turn's user message, not yet persisted

        ChatModel model = modelOverride != null ? modelOverride : llmSelector.select(persona, sessionId);
        List<ToolSpec> belt = toolBelt.tools();

        String reply = supervisorGraph.run(new GraphTurnRequest(sessionId, id, model, belt, messages));

        long turnId = memory.recordTurn(sessionId, userText, reply);
        caprRecorder.recordPassed(sessionId, id.value(), turnId);
        return reply;
    }

    /** Identity of the resolved per-agent instance — lets tests assert per-agent isolation/caching. */
    public int identity() {
        return System.identityHashCode(this);
    }
}
