package ai.forvum.engine.agent;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.pairing.DeviceRegistry;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.engine.session.compaction.CompactionPolicy;
import ai.forvum.engine.session.compaction.SessionCompactor;
import ai.forvum.sdk.ChannelTurnDriver;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
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

    /**
     * #53 multi-user toggle. When false (default) every turn binds the {@code "default"} tenant identity,
     * so per-identity state collapses to one namespace — byte-identical to single-user. When true, the
     * turn binds the RESOLVED identity, isolating that user's long-term facts from other users' (the
     * {@code "default"} namespace stays shared as the team-skill read-through).
     */
    @ConfigProperty(name = "forvum.multi-user.enabled", defaultValue = "false")
    boolean multiUserEnabled;

    @Inject
    DeviceRegistry devices;

    @Inject
    OutputGuardChain outputGuards;

    /**
     * Drive a turn for an inbound {@link ChannelMessage}, rendering it to {@code sink}. The session is
     * keyed {@code channelId:nativeUserId} (one conversation per user per channel) and carries the
     * effective identity resolved by {@link IdentityResolver#resolveEffective} (#168 precedence: resolved
     * channel identity, then the agent's {@code identityId} fallback, then the restricted anonymous). A
     * failed turn is surfaced to {@code sink} as a terminal {@link ErrorEvent} rather than propagated, so
     * a self-driving channel that runs this on its own thread does not crash its callback (which would
     * close the connection with nothing shown).
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

            registry.getOrCreate(agentId);
            Persona persona = registry.persona(agentId);
            // #168 identity precedence: a resolved channel identity, else the agent's declared identityId
            // fallback, else the deliberately restricted anonymous identity. Fails CLOSED (throws) when the
            // agent names an undefined fallback, so an unresolved user never escalates to the permissive
            // anonymous default. The effective identity is what the turn is attributed + authorized under.
            EffectiveIdentity effective = identities.resolveEffective(
                    message.channelId(), message.nativeUserId(), persona.identityId());
            String identityId = effective.identityId();
            sessions.ensureSession(sessionId, agentId, identityId, message.channelId());

            // P2-COMPACT: eagerly compact the session BEFORE the turn runs, so the agent always reads a
            // pre-compacted window (CE Compress, ULTRAPLAN section 7.2 item 20). A session under the
            // reserve floor is a no-op; the prefix (id <= cachedPrefixEndIndex) is never mutated.
            compactor.compact(sessionId, agentId.value(),
                    new CompactionPolicy(reserveFloorTokens, retainTokens));

            // P2-11 RBAC + #168: resolve the caller's effective scopes from the EFFECTIVE identity's roles
            // (the restricted anonymous role for an unresolved/no-fallback session; the permissive default
            // only for a RESOLVED identity that declares none) and bind them so ToolExecutor can gate each
            // tool's required scope. Mirrors the CURRENT_AGENT/CURRENT_TURN binds.
            Set<PermissionScope> effectiveScopes = roles.effectiveScopes(effective.roleNames());

            // P2-14 #39: bind the originating user message so a confirm-required tool parked this turn can
            // be re-dispatched from it after a restart (R1). ScopedValue forbids a null binding, so a
            // null/blank content (which cannot be meaningfully replayed anyway) binds the empty string.
            String userMessage = message.content() == null ? "" : message.content();
            // #53: bind the multi-user tenant key — the resolved identity when multi-user is on, else the
            // shared "default" namespace (single-user, byte-identical). AgentMemory scopes facts by it.
            String tenantIdentity = tenantIdentity(identityId);
            String reply = ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId)
                    .where(CurrentAgent.CURRENT_TURN, turnId)
                    .where(CurrentAgent.CURRENT_USER_MESSAGE, userMessage)
                    .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effectiveScopes)
                    .where(CurrentIdentity.CURRENT_IDENTITY_ID, tenantIdentity)
                    .call(() -> agent.respond(sessionId, message.content()));

            // P2-OUTPUTGUARD pre-channel-emit seam (DR-6a §9.2): run the composed OutputGuard chain over
            // the reply before it reaches the channel. A Redacted disposition returns the masked text (the
            // user still gets an answer, minus the secret); a Blocked disposition throws
            // OutputFilteredException, caught below as a terminal output_filtered error rather than leak.
            // Only the EGRESS is filtered — the model transcript already persisted by agent.respond is
            // untouched (PRE_MEMORY_WRITE is reserved, not wired in v0.1).
            String egress = outputGuards.enforce(
                    new OutputContext(HookLayer.PRE_CHANNEL_EMIT, agentId, turnId), reply);

            ModelRef model = persona.primaryModel();
            Instant now = Instant.now();
            sink.accept(new TokenDelta(now, egress, model));
            sink.accept(new Done(now, turnId, egress));
        } catch (OutputFilteredException filtered) {
            // An OutputGuard suppressed the egress rather than leak a secret/PII. Surface the turn on the
            // FallbackReasons.FILTERED path as a terminal output_filtered ErrorEvent — never the candidate.
            sink.accept(ErrorEvent.from(Instant.now(), turnId, "output_filtered",
                    filtered.getMessage(), filtered));
        } catch (IdentityResolutionException unresolved) {
            // #168: the agent's declared identityId fallback names no configured identity. Fail CLOSED with
            // an actionable terminal error rather than degrade an unresolved user to the permissive
            // anonymous default. Thrown before any session/scope binding, so nothing ran and nothing
            // escalated.
            sink.accept(ErrorEvent.from(Instant.now(), turnId, "identity_unresolved",
                    unresolved.getMessage(), unresolved));
        } catch (RuntimeException e) {
            // A failed turn (model/network failure, fallback exhaustion, a persistence error) must not
            // escape a self-driving channel's callback. Surface it as a terminal ErrorEvent; the failed
            // attempt is already ledgered in provider_calls by the model decorator's own transaction
            // (M7), so no conversational rows are orphaned.
            sink.accept(ErrorEvent.from(Instant.now(), turnId, "turn_failed",
                    describeFailure(agentId, e), e));
        }
    }

    /**
     * The multi-user tenant key to bind for this turn (#53): the resolved identity when multi-user is on,
     * the shared {@code "default"} namespace when off (single-user, byte-identical). Package-private so the
     * toggle ternary is red-checkable without booting a turn.
     */
    String tenantIdentity(String resolvedIdentity) {
        return multiUserEnabled ? resolvedIdentity : CurrentIdentity.DEFAULT_IDENTITY;
    }

    /**
     * Compose the user-facing failure message: the exception's own message plus the deepest cause (the
     * original network/provider failure an intermediate wrapper like the supervisor-graph exception
     * hides), and — for a connection-level failure — a hint naming the agent's configured model, since
     * an unreachable provider (model server down, wrong base URL) is the most common fresh-install
     * failure and the wrapper message alone is unactionable.
     */
    private String describeFailure(AgentId agentId, RuntimeException e) {
        String message = (e.getMessage() == null || e.getMessage().isBlank())
                ? e.getClass().getSimpleName()
                : e.getMessage();
        Throwable root = e;
        // hop cap: initCause only rejects a DIRECT self-cause, so a multi-node cause cycle is
        // constructible and would otherwise spin this walk forever inside the dispatch catch block
        for (int hops = 0; hops < 50 && root.getCause() != null && root.getCause() != root; hops++) {
            root = root.getCause();
        }
        if (root != e) {
            String rootText = (root.getMessage() == null || root.getMessage().isBlank())
                    ? ""
                    : ": " + root.getMessage();
            message += " (cause: " + root.getClass().getSimpleName() + rootText + ")";
        }
        if (isConnectionFailure(root)) {
            message += ". Is the model provider running? (model: " + primaryModelOrUnknown(agentId) + ")";
        }
        return message;
    }

    /**
     * True for the root causes an unreachable provider produces: {@link ConnectException} (JVM HTTP
     * client), {@link ClosedChannelException} (the same refusal as surfaced by the JDK client inside a
     * native image), {@link UnknownHostException} (wrong base URL host), and
     * {@link HttpConnectTimeoutException} (unroutable host). Package-private for the unit test.
     */
    static boolean isConnectionFailure(Throwable root) {
        return root instanceof ConnectException
                || root instanceof ClosedChannelException
                || root instanceof UnknownHostException
                || root instanceof HttpConnectTimeoutException;
    }

    /** The agent's configured primary model for the failure hint; never throws from the catch path. */
    private String primaryModelOrUnknown(AgentId agentId) {
        try {
            return registry.persona(agentId).primaryModel().toString();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }
}
