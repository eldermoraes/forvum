package ai.forvum.engine.agent;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.pairing.DeviceRegistry;
import ai.forvum.engine.session.compaction.CompactionPolicy;
import ai.forvum.engine.session.compaction.SessionCompactor;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Set;
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
 *
 * <p>It is the single implementation of the SDK {@link ChannelTurnDriver} contract: promoting that
 * interface to {@code forvum-sdk} lets a Layer-3 channel inject the driver depending only on
 * {@code forvum-sdk} (+ {@code forvum-core}), never on the engine (CLAUDE.md section 3 / 12).
 */
@ApplicationScoped
public class TurnService implements ChannelTurnDriver {

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

    @Inject
    RoleRegistry roles;

    @Inject
    SessionCompactor compactor;

    /** Reserve-token floor above which the session is eagerly compacted before the turn (P2-COMPACT). */
    @ConfigProperty(name = "forvum.compaction.reserve-floor-tokens", defaultValue = "8000")
    int reserveFloorTokens;

    /** Token budget the most-recent retained turns must fit within after a compaction pass. */
    @ConfigProperty(name = "forvum.compaction.retain-tokens", defaultValue = "6000")
    int retainTokens;

    @Inject
    DeviceRegistry devices;

    /**
     * Drive a turn for an inbound {@link ChannelMessage}, rendering it to {@code sink}. The session is
     * keyed {@code channelId:nativeUserId} (one conversation per user per channel) and carries the
     * resolved identity (or {@link #ANONYMOUS_IDENTITY}). A failed turn is surfaced to {@code sink} as a
     * terminal {@link ErrorEvent} rather than propagated, so a self-driving channel that runs this on
     * its own thread does not crash its callback (which would close the connection with nothing shown).
     *
     * <p>{@code @ActivateRequestContext}: a channel drives a turn from its own thread (a WebSocket
     * virtual thread, a stdin loop, a long-poll worker) with no ambient CDI request context, but the
     * turn reads conversation history through the request-scoped {@code EntityManager}. Activating the
     * context here makes the facade self-sufficient on any channel thread (the {@code @Transactional}
     * writes inside {@code recordTurn}/{@code ensureSession} open their own transactions); without it
     * the read throws {@code ContextNotActiveException}.
     */
    @Override
    @ActivateRequestContext
    public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
        AgentId agentId = new AgentId(DEFAULT_AGENT);
        String sessionId = message.channelId() + ":" + message.nativeUserId();
        UUID turnId = UUID.randomUUID();

        try {
            // P2-4 device pairing: reject an unpaired/revoked device BEFORE the responder runs. The
            // channelId is the device endpoint id (one devices/<channelId>.json declares its pairing).
            // A cheap no-op for a known-good device, and entirely disabled until devices/ is configured
            // (opt-in, no migration). The cli device (forvum ask, the host's trusted primary surface) is
            // exempt so enabling pairing never locks out the operator's own terminal. cron turns NEVER
            // reach here — CronScheduler.fire calls agent.respond directly — so cron is exempt BY
            // CONSTRUCTION; the cron/server entries in EXEMPT are a defensive belt should that ever change.
            devices.requirePaired(message.channelId());

            String identityId = identities.resolveIdentityId(message.channelId(), message.nativeUserId())
                    .orElse(ANONYMOUS_IDENTITY);
            registry.getOrCreate(agentId);
            sessions.ensureSession(sessionId, agentId, identityId, message.channelId());

            // P2-COMPACT: eagerly compact the session BEFORE the turn runs, so the agent always reads a
            // pre-compacted window (CE Compress, ULTRAPLAN section 7.2 item 20). A session under the
            // reserve floor is a no-op; the prefix (id <= cachedPrefixEndIndex) is never mutated.
            compactor.compact(sessionId, agentId.value(),
                    new CompactionPolicy(reserveFloorTokens, retainTokens));

            // P2-11 RBAC: resolve the caller's effective scopes (union of its roles' scope-sets, or the
            // permissive default role for an identity that declares none) and bind them for the turn so
            // ToolExecutor can gate each tool's required scope. Mirrors the CURRENT_AGENT/CURRENT_TURN binds.
            Set<PermissionScope> effectiveScopes = roles.effectiveScopes(identities.rolesFor(identityId));

            String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId)
                    .where(CurrentAgent.CURRENT_TURN, turnId)
                    .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effectiveScopes)
                    .call(() -> agent.respond(sessionId, message.content()));

            ModelRef model = registry.persona(agentId).primaryModel();
            Instant now = Instant.now();
            sink.accept(new TokenDelta(now, reply, model));
            sink.accept(new Done(now, turnId, reply));
        } catch (RuntimeException e) {
            // A failed turn (model/network failure, fallback exhaustion, a persistence error) must not
            // escape a self-driving channel's callback. Surface it as a terminal ErrorEvent; the failed
            // attempt is already ledgered in provider_calls by the model decorator's own transaction
            // (M7), so no conversational rows are orphaned.
            sink.accept(ErrorEvent.from(Instant.now(), turnId, "turn_failed", e.getMessage(), e));
        }
    }
}
