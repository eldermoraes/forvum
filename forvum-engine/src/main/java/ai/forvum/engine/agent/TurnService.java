package ai.forvum.engine.agent;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.DeviceCredential;
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
import ai.forvum.engine.pairing.Device;
import ai.forvum.engine.pairing.DeviceNotPairedException;
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
import java.util.EnumSet;
import java.util.Optional;
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
        // Backward-compatible entry: a channel with no per-connection credential (TUI, the long-poll bots,
        // the operator/local surfaces) drives the turn with DeviceCredential.ABSENT — the P2-4
        // paired-by-existence path. @ActivateRequestContext is on this public entry, so the private
        // delegate runs inside the request context it activates (no self-re-interception needed).
        dispatchAuthenticated(message, DeviceCredential.ABSENT, sink);
    }

    /**
     * Drive a turn carrying the {@link DeviceCredential} the channel adapter authenticated the connection
     * with (#166). The Web channel calls this overload; the credential is authenticated against the paired
     * device (timing-safe token compare, channel bind {@code deviceId == channelId}, revocation) BEFORE the
     * responder runs, and the device's {@code approvedScopes} are intersected into the turn's scopes.
     */
    @Override
    @ActivateRequestContext
    public void dispatch(ChannelMessage message, DeviceCredential credential, Consumer<AgentEvent> sink) {
        dispatchAuthenticated(message, credential, sink);
    }

    private void dispatchAuthenticated(ChannelMessage message, DeviceCredential credential,
            Consumer<AgentEvent> sink) {
        AgentId agentId = new AgentId(DEFAULT_AGENT);
        String sessionId = message.channelId() + ":" + message.nativeUserId();
        UUID turnId = UUID.randomUUID();

        try {
            // #166 device authentication: authenticate the device the turn arrives on BEFORE the responder
            // runs. ABSENT (the two-arg entry) keeps the backward-compatible P2-4 paired-by-existence path;
            // a PRESENT credential (the Web channel) is bound to the channel + timing-safe token-checked.
            // An unpaired/revoked/unauthenticated device throws DeviceNotPairedException (or its
            // DeviceAuthenticationException subtype), surfaced below as a terminal ErrorEvent before any
            // model/tool runs. Disabled until devices/ is configured (opt-in); cli/cron/server exempt.
            Optional<Device> device = devices.authenticate(message.channelId(), credential);

            registry.getOrCreate(agentId);
            Persona persona = registry.persona(agentId);
            // #168 identity precedence: a resolved channel identity, else the agent's declared identityId
            // fallback, else the deliberately restricted anonymous identity. Fails CLOSED (throws) when the
            // agent names an undefined fallback, so an unresolved user never escalates to the permissive
            // anonymous default. The effective identity is what the turn is attributed + authorized under.
            EffectiveIdentity effective = identities.resolveEffective(
                    message.channelId(), message.nativeUserId(), persona.identityId());
            String identityId = effective.identityId();

            // P2-11 RBAC + #168 + #167: the caller's effective scopes (#168: the restricted anonymous role
            // for an unresolved/no-fallback session; the permissive default only for a RESOLVED identity
            // that declares none), THEN capped by the selected agent's declared role ceiling (#167 / DR-8
            // DP-8: effective = callerScopes ∩ the union of persona.roles()' scope-sets; an empty agent role
            // list is no cap — the cap can only ever RESTRICT, never widen, so an agent cannot grant a scope
            // the caller lacks). This single value is bound into CURRENT_EFFECTIVE_SCOPES, so tool discovery
            // (the model-facing belt) and execution-time authorization observe ONE consistent capped set. It
            // is resolved BEFORE any session/turn state is created so a misconfiguration touches nothing.
            Set<PermissionScope> effectiveScopes;
            try {
                Set<PermissionScope> callerScopes = roles.effectiveScopes(effective.roleNames());
                Set<PermissionScope> capped = roles.capScopes(callerScopes, persona.roles());
                // #166: when the turn authenticated AS A DEVICE (a credential was presented), additionally
                // intersect that device's approvedScopes. approvedScopes govern a PAIRED DEVICE — not the
                // host operator / local surface, which presents no device credential (the ABSENT path keeps
                // its full caller/agent scopes). Opt-in-governed: a device declaring no scopes does not cap;
                // only approvedScopes are intersected, so a PENDING upgrade (requested-but-not-approved)
                // never appears in CURRENT_EFFECTIVE_SCOPES.
                effectiveScopes = credential.present()
                        ? intersectDeviceApprovedScopes(capped, device)
                        : capped;
            } catch (IllegalStateException roleError) {
                // The identity OR the agent names a role that resolves to no built-in and no
                // roles/<name>.json. Fail CLOSED with an actionable config error rather than guess at scopes
                // or leave the caller's full set in force (acceptance #4). The diagnostic names the offending
                // role + file and carries no caller scopes. Nothing has run yet, so nothing escalated.
                sink.accept(ErrorEvent.from(Instant.now(), turnId, "role_unresolved",
                        roleError.getMessage(), roleError));
                return;
            }

            sessions.ensureSession(sessionId, agentId, identityId, message.channelId());

            // P2-COMPACT: eagerly compact the session BEFORE the turn runs, so the agent always reads a
            // pre-compacted window (CE Compress, ULTRAPLAN section 7.2 item 20). A session under the
            // reserve floor is a no-op; the prefix (id <= cachedPrefixEndIndex) is never mutated.
            compactor.compact(sessionId, agentId.value(),
                    new CompactionPolicy(reserveFloorTokens, retainTokens));

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
        } catch (DeviceNotPairedException deviceRejected) {
            // #166 + P2-4: the device is unpaired, revoked, or presented an invalid / missing /
            // cross-channel credential (the DeviceAuthenticationException subtype). Rejected BEFORE the
            // responder, so no model/provider/tool ran. The message carries no token (secret hygiene, #166).
            sink.accept(ErrorEvent.from(Instant.now(), turnId, "device_unpaired",
                    deviceRejected.getMessage(), deviceRejected));
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
     * Intersect the turn's already-authorized scopes with the paired device's {@code approvedScopes}
     * when the device opts into scope governance (#166, the ratified opt-in-governed semantics). A device
     * that declares neither requested nor approved scopes does NOT cap — the scopes pass through unchanged
     * (backward compatible with P2-4 device files); {@link Optional#empty()} (pairing disabled) likewise
     * does not cap. A scope-governed device intersects, so a PENDING upgrade (in {@code requestedScopes}
     * but not yet {@code approvedScopes}) never reaches {@code CURRENT_EFFECTIVE_SCOPES} — only
     * {@code approvedScopes} are intersected. Package-private + static so the rule is unit-testable
     * without booting a turn.
     */
    static Set<PermissionScope> intersectDeviceApprovedScopes(Set<PermissionScope> scopes,
            Optional<Device> device) {
        if (device.isEmpty()) {
            return scopes; // pairing disabled — no device to cap by
        }
        Device d = device.get();
        boolean scopeGoverned = !d.requestedScopes().isEmpty() || !d.approvedScopes().isEmpty();
        if (!scopeGoverned) {
            return scopes; // a device declaring no scope governance does not restrict (opt-in)
        }
        Set<PermissionScope> intersected = EnumSet.noneOf(PermissionScope.class);
        intersected.addAll(scopes);
        intersected.retainAll(d.approvedScopes()); // caller ∩ agent-cap ∩ device-approved
        return intersected;
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
