package ai.forvum.engine.agent;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The channel turn-driver facade (ULTRAPLAN section 5.3 / 5.5): the single seam every channel (web,
 * tui, telegram) calls to run a turn. It resolves identity, binds {@code CURRENT_AGENT} +
 * {@code CURRENT_TURN}, drives the single-shot {@code Agent.respond}, and renders the turn as a stream
 * of {@link AgentEvent} to the caller's {@code sink} — replacing the registry+ScopedValue+respond dance
 * that previously lived only in tests/e2e.
 *
 * <p><strong>v0.1 streaming (Option B):</strong> the single-shot {@code String} reply becomes one
 * {@link TokenDelta} then a terminal {@link Done}. The outbound type is {@code Consumer<AgentEvent>} so
 * true per-token streaming is a later non-breaking upgrade — it arrives with the M18 LangGraph4j
 * {@code SupervisorGraph}, the sanctioned {@code AgentEvent}-per-node producer.
 */
@ApplicationScoped
public class TurnService {

    /** v0.1 routes every channel turn to the canonical {@code main} agent. */
    static final String DEFAULT_AGENT = "main";

    /** Session identity when the native user matches no configured {@code Identity}. */
    static final String ANONYMOUS_IDENTITY = "anonymous";

    @Inject
    AgentRegistry registry;

    @Inject
    Agent agent;

    @Inject
    SessionManager sessions;

    @Inject
    IdentityResolver identities;

    /**
     * Drive a turn for an inbound {@link ChannelMessage}, rendering it to {@code sink}. The session is
     * keyed {@code channelId:nativeUserId} (one conversation per user per channel) and carries the
     * resolved identity (or {@link #ANONYMOUS_IDENTITY}).
     */
    public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
        AgentId agentId = new AgentId(DEFAULT_AGENT);
        String sessionId = message.channelId() + ":" + message.nativeUserId();
        String identityId = identities.resolveIdentityId(message.channelId(), message.nativeUserId())
                .orElse(ANONYMOUS_IDENTITY);
        UUID turnId = UUID.randomUUID();

        registry.getOrCreate(agentId);
        sessions.ensureSession(sessionId, agentId, identityId, message.channelId());

        String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId)
                .where(CurrentAgent.CURRENT_TURN, turnId)
                .call(() -> agent.respond(sessionId, message.content()));

        ModelRef model = registry.persona(agentId).primaryModel();
        Instant now = Instant.now();
        sink.accept(new TokenDelta(now, reply, model));
        sink.accept(new Done(now, turnId, reply));
    }
}
