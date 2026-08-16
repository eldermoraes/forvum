package ai.forvum.engine.sessions;

import ai.forvum.core.BlockType;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.agent.IdentityResolver;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionMessage;
import ai.forvum.sdk.SessionSummary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The engine implementation of the #189 {@link SessionAccess} seam — the backend for the model-callable
 * {@code agents.list} / {@code sessions.list} / {@code sessions.history} / {@code sessions.send} tool
 * ({@code forvum-tools-sessions}). It layers a read/dispatch surface on EXISTING machinery, introducing
 * no new schema and no new turn path:
 * <ul>
 *   <li><b>agentIds</b> reads the configured {@code agents/<id>.json} ids from the M4 {@link AgentReader}
 *       (the same source {@code AgentRegistry.getOrCreate} loads from);</li>
 *   <li><b>sessions/history</b> read the {@code sessions}/{@code messages} ledger via the existing
 *       Panache entities;</li>
 *   <li><b>send</b> dispatches a FULL turn into the target session through the same
 *       {@link ChannelTurnDriver} ({@code TurnService}) every channel drives — the target session's agent
 *       responds and the exchange is persisted into the target transcript, exactly as if the message had
 *       arrived on the target's own channel (identity resolution, RBAC, compaction, output guards all
 *       re-run for the nested turn).</li>
 * </ul>
 *
 * <p><b>Fail-closed identity scoping</b> (#170/#167): the caller's identity is the #53
 * {@code CURRENT_IDENTITY_ID} tenant binding — bound at every production turn entry. Unbound (a
 * non-turn caller) or the restricted {@link IdentityResolver#ANONYMOUS_IDENTITY} sees NOTHING. The
 * single-user namespace collapse sees every session — but ONLY while {@code forvum.multi-user.enabled}
 * is off (#189 audit: the god-view is gated on the TOGGLE, not on the {@code "default"} identity NAME,
 * so in a multi-user deployment a caller who resolves to the literal {@code default} identity is an
 * ordinary tenant, not an omniscient one). With multi-user on, EVERY identity — {@code default}
 * included — sees only sessions whose {@code sessions.identity_id} matches. An invisible session and a
 * nonexistent one produce the SAME diagnostic, so the seam is not a cross-tenant existence oracle.
 *
 * <p><b>Delivery-loop guard:</b> {@code send} dispatches synchronously on the calling virtual thread, so
 * a target turn that itself calls {@code sessions.send} would recurse unboundedly (two sessions
 * ping-ponging). A {@link ScopedValue} latch bound around the nested dispatch refuses a re-entrant send,
 * and a send targeting the CALLING turn's own session ({@link CurrentAgent#CURRENT_SESSION_ID}) is
 * refused outright (#189 audit).
 *
 * <p><b>Relay hardening (#189 audit):</b> the nested dispatch (a) re-binds the calling turn's
 * {@code CURRENT_EFFECTIVE_SCOPES} as {@link CurrentIdentity#INHERITED_SCOPE_CAP} so the target turn
 * runs under {@code its scopes ∩ the relayer's} — the #166 device cap survives the relay; (b) binds
 * {@link ApprovalContext#NON_INTERACTIVE} — a relayed turn has no approval surface, so a
 * confirm-required tool denies at once instead of parking forever (D6.5); and (c) prefixes the
 * delivered content with a provenance marker so the target transcript records the message as relayed,
 * not as the target user speaking (D6.6). Every seam method activates its own request context
 * ({@code @ActivateRequestContext}) so a cron/one-shot caller with no ambient request scope does not
 * die with {@code ContextNotActiveException} (the PanachePlanStore pattern).
 */
@ApplicationScoped
public class EngineSessionAccess implements SessionAccess {

    private static final Logger LOG = Logger.getLogger(EngineSessionAccess.class);

    /** Bound while a {@code sessions.send} turn is being dispatched — a nested send is refused. */
    private static final ScopedValue<Boolean> IN_SEND = ScopedValue.newInstance();

    @Inject
    AgentReader agents;

    @Inject
    ChannelTurnDriver turns;

    /** #53/#189: the god-view collapse applies only while multi-user is OFF (gated on the toggle). */
    @Inject
    @ConfigProperty(name = "forvum.multi-user.enabled", defaultValue = "false")
    boolean multiUserEnabled;

    @Override
    @ActivateRequestContext
    public List<String> agentIds() {
        if (callerIdentity().isEmpty()) {
            return List.of();
        }
        return List.copyOf(agents.ids());
    }

    @Override
    @ActivateRequestContext
    public List<SessionSummary> sessions() {
        Optional<String> caller = callerIdentity();
        if (caller.isEmpty()) {
            return List.of();
        }
        List<SessionEntity> rows = visibleRows(caller.get());
        List<SessionSummary> summaries = new ArrayList<>(rows.size());
        for (SessionEntity row : rows) {
            summaries.add(new SessionSummary(row.id, row.agentId, row.channelId, row.startedAt, row.lastSeenAt));
        }
        return List.copyOf(summaries);
    }

    @Override
    @ActivateRequestContext
    public List<SessionMessage> history(String sessionId, int limit) {
        SessionEntity target = visibleOrThrow(sessionId);
        int bounded = Math.max(1, limit);
        // Transcript rows only: internal scratchpad blocks (plan / turn_reasoning / turn_artifact /
        // tool_execution) never surface through sessions.history.
        List<MessageEntity> rows = MessageEntity
                .<MessageEntity>find("sessionId = ?1 and blockType = ?2 order by id desc",
                        target.id, BlockType.TURN_MESSAGE.dbValue())
                .page(0, bounded)
                .list();
        List<SessionMessage> messages = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) { // re-reverse: oldest first for the model
            MessageEntity row = rows.get(i);
            messages.add(new SessionMessage(row.role, row.content, row.createdAt));
        }
        return List.copyOf(messages);
    }

    @Override
    @ActivateRequestContext
    public String send(String sessionId, String message) {
        SessionEntity target = visibleOrThrow(sessionId);
        if (IN_SEND.isBound()) {
            throw new IllegalStateException(
                    "sessions.send cannot be called from a turn that is itself a sessions.send delivery — "
                  + "refusing a delivery loop between sessions.");
        }
        if (CurrentAgent.CURRENT_SESSION_ID.isBound()
                && CurrentAgent.CURRENT_SESSION_ID.get().equals(target.id)) {
            throw new IllegalStateException(
                    "sessions.send cannot target the calling turn's own session '" + sessionId
                  + "' — refusing a self-delivery loop.");
        }
        // The engine keys channel sessions 'channelId:nativeUserId' (TurnService.dispatch); a session
        // whose id does not embed its own channel (an internal-path row) has no dispatchable address.
        String prefix = target.channelId + ":";
        if (!target.id.startsWith(prefix) || target.id.length() == prefix.length()) {
            throw new IllegalStateException(
                    "Session '" + sessionId + "' is not addressable for delivery: only channel sessions "
                  + "(keyed channelId:nativeUserId) accept sessions.send.");
        }
        List<AgentEvent> events = dispatchRelayed(target, message);

        for (AgentEvent event : events) {
            if (event instanceof Done done) {
                return done.finalMessage();
            }
            if (event instanceof ErrorEvent error) {
                // #172 posture: log the detail server-side, surface only a sanitized code-level diagnostic.
                LOG.warnf("sessions.send into '%s' failed: %s (%s)", sessionId, error.code(), error.message());
                throw new IllegalStateException(
                        "Delivery into session '" + sessionId + "' failed (" + error.code() + ").");
            }
        }
        throw new IllegalStateException(
                "Delivery into session '" + sessionId + "' produced no terminal event.");
    }

    /**
     * Dispatch the relayed turn into {@code target} with the #189 audit bindings: the loop latch, the
     * caller's effective scopes as the {@link CurrentIdentity#INHERITED_SCOPE_CAP} (when the calling turn
     * bound any — a non-turn caller inherits no cap), {@link ApprovalContext#NON_INTERACTIVE} (a relayed
     * turn has no approval surface), and the provenance-prefixed content. Package-private seam so the
     * binding contract is unit-testable with a stub {@link ChannelTurnDriver} and a hand-built
     * {@link SessionEntity} — no container, no DB.
     */
    List<AgentEvent> dispatchRelayed(SessionEntity target, String message) {
        String nativeUserId = target.id.substring(target.channelId.length() + 1);
        ChannelMessage delivery = new ChannelMessage(
                target.channelId, nativeUserId, provenancePrefixed(message), Instant.now());

        var carrier = ScopedValue.where(IN_SEND, Boolean.TRUE)
                .where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE);
        if (CurrentIdentity.CURRENT_EFFECTIVE_SCOPES.isBound()) {
            Set<PermissionScope> callerScopes = CurrentIdentity.CURRENT_EFFECTIVE_SCOPES.get();
            carrier = carrier.where(CurrentIdentity.INHERITED_SCOPE_CAP, callerScopes);
        }
        List<AgentEvent> events = new ArrayList<>();
        carrier.run(() -> turns.dispatch(delivery, events::add));
        return events;
    }

    /** The D6.6 provenance marker: the target transcript must record a relayed message as relayed. */
    private static String provenancePrefixed(String message) {
        String origin = CurrentAgent.CURRENT_SESSION_ID.isBound()
                ? " from session '" + CurrentAgent.CURRENT_SESSION_ID.get() + "'"
                : "";
        return "[relayed via sessions.send" + origin + "] " + message;
    }

    /**
     * The caller's tenant identity, or empty when the read/write must fail closed: the scope binding is
     * absent (a non-turn caller) or the identity is the restricted unresolved {@code anonymous}.
     */
    private Optional<String> callerIdentity() {
        if (!CurrentIdentity.CURRENT_IDENTITY_ID.isBound()) {
            return Optional.empty();
        }
        String identity = CurrentIdentity.CURRENT_IDENTITY_ID.get();
        if (IdentityResolver.ANONYMOUS_IDENTITY.equals(identity)) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    private List<SessionEntity> visibleRows(String caller) {
        if (!multiUserEnabled && CurrentIdentity.DEFAULT_IDENTITY.equals(caller)) {
            return SessionEntity.list("order by lastSeenAt desc, id");
        }
        return SessionEntity.list("identityId = ?1 order by lastSeenAt desc, id", caller);
    }

    /** The session iff it exists AND is caller-visible — one diagnostic for both failures (no oracle). */
    private SessionEntity visibleOrThrow(String sessionId) {
        Optional<String> caller = callerIdentity();
        SessionEntity target = caller.isEmpty() ? null : SessionEntity.findById(sessionId);
        boolean godView = !multiUserEnabled && CurrentIdentity.DEFAULT_IDENTITY.equals(caller.orElse(null));
        if (target == null || (!godView && !caller.get().equals(target.identityId))) {
            throw new IllegalArgumentException(
                    "No session '" + sessionId + "' is visible to the caller. Use sessions.list to see "
                  + "the sessions you can access.");
        }
        return target;
    }
}
