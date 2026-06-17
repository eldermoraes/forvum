package ai.forvum.engine.approval;

import ai.forvum.engine.persistence.ApprovalEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence seam for the {@code tool_approvals} queue (P2-14 #39). A separate {@code @Transactional}
 * bean — NOT inlined into {@link ApprovalService} — so each write commits in its own short transaction
 * through the CDI proxy (self-invocation would bypass the interceptor) and {@code ApprovalService} can
 * BLOCK between {@link #createPending} and {@link #resolve} without holding a database connection across
 * the wait (CLAUDE.md section 14, the [M7] "never block holding a transaction" rule).
 *
 * <p>Every method is {@code @ActivateRequestContext} so it works on any thread — the turn's own thread
 * (which already has a context), a one-shot/cron thread, and the dashboard's blocked-turn thread — by
 * activating a request context for the request-scoped {@code EntityManager} when none is ambient (the
 * [M16] lesson). Each operation is self-contained (no managed entity crosses the boundary), so a nested
 * context is harmless.
 */
@ApplicationScoped
public class ApprovalStore {

    /** Insert a fresh {@code pending} row and return its UUID id. */
    @Transactional
    @ActivateRequestContext
    public String createPending(String sessionId, String agentId, String toolName, String arguments,
            String userMessage) {
        ApprovalEntity e = new ApprovalEntity();
        e.id = UUID.randomUUID().toString();
        e.sessionId = sessionId;
        e.agentId = agentId;
        e.toolName = toolName;
        e.arguments = arguments;
        e.userMessage = userMessage;
        e.status = ApprovalStatus.PENDING.dbValue();
        e.createdAt = System.currentTimeMillis();
        e.persist();
        return e.id;
    }

    /** Move a row to a terminal state with the decision reason and resolution timestamp. */
    @Transactional
    @ActivateRequestContext
    public void resolve(String id, ApprovalStatus status, String reason) {
        ApprovalEntity e = ApprovalEntity.findById(id);
        if (e == null) {
            return;
        }
        e.status = status.dbValue();
        e.decisionReason = reason;
        e.resolvedAt = System.currentTimeMillis();
        e.persist();
    }

    /** The status {@code dbValue} of a row, or {@code null} if it does not exist. */
    @Transactional
    @ActivateRequestContext
    public String statusOf(String id) {
        ApprovalEntity e = ApprovalEntity.findById(id);
        return e == null ? null : e.status;
    }

    /** Every still-{@code pending} approval, oldest first, for the dashboard. */
    @Transactional
    @ActivateRequestContext
    public List<PendingApproval> listPending() {
        return ApprovalEntity.<ApprovalEntity>find(
                        "status = ?1 order by createdAt asc, id asc", ApprovalStatus.PENDING.dbValue())
                .list().stream()
                .map(e -> new PendingApproval(e.id, e.sessionId, e.agentId, e.toolName, e.arguments,
                        e.createdAt))
                .toList();
    }

    /** The most recent row id for a session (used by the sync resolution path / tests). */
    @Transactional
    @ActivateRequestContext
    public Optional<String> idForSession(String sessionId) {
        ApprovalEntity e = ApprovalEntity.<ApprovalEntity>find(
                "sessionId = ?1 order by createdAt desc, id desc", sessionId).firstResult();
        return Optional.ofNullable(e).map(row -> row.id);
    }

    /** The stored original user message of a row, for R1 re-dispatch; empty if absent. */
    @Transactional
    @ActivateRequestContext
    public Optional<String> userMessageFor(String id) {
        ApprovalEntity e = ApprovalEntity.findById(id);
        return Optional.ofNullable(e).map(row -> row.userMessage);
    }

    /** A still-{@code pending} row's identity for re-dispatch after restart, or empty if not pending. */
    @Transactional
    @ActivateRequestContext
    public Optional<PendingApproval> findPending(String id) {
        ApprovalEntity e = ApprovalEntity.findById(id);
        if (e == null || !ApprovalStatus.PENDING.dbValue().equals(e.status)) {
            return Optional.empty();
        }
        return Optional.of(new PendingApproval(e.id, e.sessionId, e.agentId, e.toolName, e.arguments,
                e.createdAt));
    }
}
