package ai.forvum.engine.approval;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ApprovalPrompter;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The P2-14 #39 blocking, SQLite-backed user-approval gate for {@code USER_CONFIRM_REQUIRED} tool calls
 * (ULTRAPLAN section 7.2 item 14 / section 9.1.b DP-9). When the {@code ToolExecutor} reaches a
 * confirm-required tool (after the belt + RBAC gates), it calls {@link #requireApproval} and BLOCKS the
 * turn's virtual thread until the owner decides — or it times out to deny. Resolution mode is chosen from
 * the {@link ApprovalContext} bindings the channel/command set for the turn:
 *
 * <ul>
 *   <li>{@code PROMPTER} bound → prompt synchronously (TTY);</li>
 *   <li>{@code NON_INTERACTIVE} true → deny immediately (one-shot/cron, no approval surface);</li>
 *   <li>otherwise → park the call in the {@code tool_approvals} queue and block on an in-memory future the
 *       web dashboard completes via {@link #decide}, timing out to deny.</li>
 * </ul>
 *
 * <p>The queue row is committed (via {@link ApprovalStore}) BEFORE blocking, so the dashboard sees the
 * pending request; the block holds no database connection (CLAUDE.md section 14 [M7]). The terminal audit
 * of the resolved call rides {@code tool_invocations} (written by {@code ToolExecutor}); this service owns
 * only the approval lifecycle. {@code ConcurrentHashMap} + {@code CompletableFuture} carry the cross-thread
 * hand-off with no {@code synchronized} (section 3.8).
 */
@ApplicationScoped
public class ApprovalService implements ApprovalGate {

    private static final Logger LOG = Logger.getLogger(ApprovalService.class);

    @Inject
    ApprovalStore store;

    /** The single channel turn-driver (the engine's {@code TurnService}); used to re-dispatch after restart. */
    @Inject
    ChannelTurnDriver turns;

    /** Seconds a parked confirm-required call waits for an out-of-band (dashboard) decision before deny. */
    @ConfigProperty(name = "forvum.approval.timeout-seconds", defaultValue = "300")
    int timeoutSeconds;

    /** Live parked calls in THIS process, keyed by approval id; the dashboard completes the future. */
    private final ConcurrentMap<String, CompletableFuture<ApprovalDecision>> pending =
            new ConcurrentHashMap<>();

    /** Single-use {@code (session,tool,args)} grants the R1 re-dispatch path consumes (see {@link #decide}). */
    private final Set<String> preApproved = ConcurrentHashMap.newKeySet();

    /** Runs an R1 re-dispatch off the decision thread; a same-thread executor in tests. */
    Executor dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public boolean requireApproval(String sessionId, AgentId agentId, String toolName, String arguments) {
        // R1: a restart-orphaned request the owner approved pre-authorizes its exact re-run (single-use).
        if (consumePreApproval(sessionId, toolName, arguments)) {
            return true;
        }
        String userMessage = CurrentAgent.CURRENT_USER_MESSAGE.isBound()
                ? CurrentAgent.CURRENT_USER_MESSAGE.get()
                : null;
        String id = store.createPending(sessionId, agentId.value(), toolName, arguments, userMessage);

        // Interactive (TTY): the channel bound a prompter — ask the owner synchronously on this thread.
        if (ApprovalContext.PROMPTER.isBound()) {
            ApprovalPrompter prompter = ApprovalContext.PROMPTER.get();
            boolean approved = prompter.promptApproval(agentId.value(), toolName, arguments);
            store.resolve(id, approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
                    approved ? "tty_approved" : "tty_rejected");
            return approved;
        }

        // Non-interactive (one-shot/cron) with no approval surface: deny at once rather than block.
        if (ApprovalContext.NON_INTERACTIVE.orElse(Boolean.FALSE)) {
            store.resolve(id, ApprovalStatus.REJECTED, "non_interactive");
            return false;
        }

        // Async: block until the web dashboard decides via decide(), or time out to deny. No DB connection
        // is held across the wait.
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ApprovalDecision decision = future.get(timeoutSeconds, TimeUnit.SECONDS);
            store.resolve(id, decision.approved() ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
                    decision.reason());
            return decision.approved();
        } catch (TimeoutException e) {
            store.resolve(id, ApprovalStatus.TIMED_OUT, "timeout");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.resolve(id, ApprovalStatus.REJECTED, "interrupted");
            return false;
        } catch (ExecutionException e) {
            LOG.warnf("Approval future for %s failed: %s", id, e.getCause());
            store.resolve(id, ApprovalStatus.REJECTED, "error");
            return false;
        } finally {
            pending.remove(id);
        }
    }

    /**
     * Resolve a parked approval from an out-of-band surface (the web dashboard / decision endpoint).
     *
     * <p>Two paths: a <strong>live</strong> call (a blocked turn in THIS process) is released by completing
     * its in-memory future. A call orphaned by a process <strong>restart</strong> (the row survived in
     * SQLite but its turn thread is gone) is resolved against the queue: a reject just records the decision;
     * an approve pre-authorizes the exact {@code (session,tool,args)} call and <strong>re-dispatches</strong>
     * the turn from its stored original message (R1 — best-effort, the model may take a different path).
     *
     * @return {@code true} if a live OR orphaned-pending row was handled; {@code false} if the id is unknown
     *         or already resolved
     */
    public boolean decide(String id, boolean approved, String reason) {
        CompletableFuture<ApprovalDecision> future = pending.get(id);
        if (future != null) {
            return future.complete(new ApprovalDecision(approved, reason));
        }
        // No live future here — an orphaned restart survivor (or an unknown/already-resolved id).
        Optional<PendingApproval> orphan = store.findPending(id);
        if (orphan.isEmpty()) {
            return false;
        }
        PendingApproval p = orphan.get();
        if (!approved) {
            store.resolve(id, ApprovalStatus.REJECTED, reason == null ? "rejected" : reason);
            return true;
        }
        markPreApproved(p.sessionId(), p.toolName(), p.arguments());
        store.resolve(id, ApprovalStatus.APPROVED, reason == null ? "approved_redispatch" : reason);
        reDispatch(p, store.userMessageFor(id).orElse(null));
        return true;
    }

    /** Re-run the turn that parked an approval, off the decision thread, from its stored original message. */
    private void reDispatch(PendingApproval p, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            LOG.warnf("Cannot re-dispatch approval %s: no stored user message.", p.id());
            return;
        }
        int sep = p.sessionId().indexOf(':');
        if (sep < 0) {
            LOG.warnf("Cannot re-dispatch approval %s: malformed session id '%s'.", p.id(), p.sessionId());
            return;
        }
        String channelId = p.sessionId().substring(0, sep);
        String nativeUserId = p.sessionId().substring(sep + 1);
        ChannelMessage message = new ChannelMessage(channelId, nativeUserId, userMessage, Instant.now());
        // NON_INTERACTIVE so any OTHER confirm-required tool in the replay denies fast (only the pre-approved
        // one auto-approves); the original requester's connection is gone, so the reply is logged, not sent.
        dispatchExecutor.execute(() ->
                ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE)
                        .run(() -> turns.dispatch(message, this::logReplayEvent)));
    }

    private void logReplayEvent(AgentEvent event) {
        LOG.debugf("Re-dispatch replay event: %s", event.getClass().getSimpleName());
    }

    private static String preApprovalKey(String sessionId, String toolName, String arguments) {
        return sessionId + ' ' + toolName + ' ' + arguments;
    }

    private void markPreApproved(String sessionId, String toolName, String arguments) {
        preApproved.add(preApprovalKey(sessionId, toolName, arguments));
    }

    /** Consume a single-use pre-approval for an exact call; package-private for the re-dispatch unit test. */
    boolean consumePreApproval(String sessionId, String toolName, String arguments) {
        return preApproved.remove(preApprovalKey(sessionId, toolName, arguments));
    }

    /** Every still-pending approval, for the dashboard. */
    public List<PendingApproval> listPending() {
        return store.listPending();
    }

    /** The status {@code dbValue} of an approval row, or {@code null} if it does not exist. */
    public String statusOf(String id) {
        return store.statusOf(id);
    }

    /** The most recent approval-row id for a session (sync resolution path / tests). */
    public Optional<String> idForSession(String sessionId) {
        return store.idForSession(sessionId);
    }
}
