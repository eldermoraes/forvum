package ai.forvum.app;

import ai.forvum.engine.approval.ApprovalService;
import ai.forvum.engine.approval.PendingApproval;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.vertx.web.Param;
import io.quarkus.vertx.web.Route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * The web approval surface for {@code USER_CONFIRM_REQUIRED} tool calls (P2-14 #39, ULTRAPLAN §7.2 item 14):
 * a {@code GET /q/dashboard/approvals} list of pending requests and {@code POST .../approve|reject} decision
 * endpoints the owner uses to release or deny a parked turn. A {@code quarkus-reactive-routes} {@code @Route}
 * over the already-present {@code vertx-http} server (the same surface as {@link CaprDashboardRoute}).
 *
 * <p><strong>Server-path only — no command-mode cold-start impact.</strong> The routes bind only when a
 * server channel (the Web channel) is up; a one-shot/command-mode run leaves {@code vertx-http} unbound
 * ({@code quarkus.http.host-enabled=false}, {@code ForvumApplication.main}) and they never serve. The bean
 * carries no {@code @Startup}/{@code StartupEvent} observer and does its DB work only inside the handler, on
 * an actual request, so it does not touch the &lt; 200 ms boot path nor the {@code ask}/{@code doctor}
 * one-shot path. {@code type = BLOCKING}: the handlers do blocking JDBC reads/writes via {@link ApprovalService}
 * (CLAUDE.md §11 — blocking work off the event loop).
 *
 * <p>Decisions route to {@link ApprovalService#decide}: a live blocked turn in this process is released
 * directly; an approval orphaned by a restart is resolved against the SQLite queue (and an approve
 * re-dispatches its turn, R1). An unknown or already-resolved id returns {@code handled=false} with HTTP
 * 200 — the client reads the flag.
 */
@ApplicationScoped
public class ApprovalDashboardRoute {

    @Inject
    ApprovalService approvals;

    /** Every still-pending confirm-required request, as JSON, for the approval card/dashboard. */
    @Route(path = "/q/dashboard/approvals", methods = Route.HttpMethod.GET,
            type = Route.HandlerType.BLOCKING, produces = "application/json")
    public List<ApprovalView> pending() {
        return approvals.listPending().stream().map(ApprovalView::from).toList();
    }

    /** Approve a parked request (release the live turn, or re-dispatch an orphaned one). */
    @Route(path = "/q/dashboard/approvals/:id/approve", methods = Route.HttpMethod.POST,
            type = Route.HandlerType.BLOCKING, produces = "application/json")
    public DecisionResult approve(@Param("id") String id) {
        return new DecisionResult(approvals.decide(id, true, "approved via dashboard"), id);
    }

    /** Reject a parked request (deny + audit; no re-dispatch). */
    @Route(path = "/q/dashboard/approvals/:id/reject", methods = Route.HttpMethod.POST,
            type = Route.HandlerType.BLOCKING, produces = "application/json")
    public DecisionResult reject(@Param("id") String id) {
        return new DecisionResult(approvals.decide(id, false, "rejected via dashboard"), id);
    }

    /** JSON view of one pending {@code tool_approvals} row. A Layer-4 (Quarkus) record → real reflection hint. */
    @RegisterForReflection
    public record ApprovalView(String id, String sessionId, String agentId, String toolName,
                               String arguments, long createdAt) {

        static ApprovalView from(PendingApproval p) {
            return new ApprovalView(p.id(), p.sessionId(), p.agentId(), p.toolName(), p.arguments(),
                    p.createdAt());
        }
    }

    /** JSON result of a decision: whether a pending request was handled, and which id. */
    @RegisterForReflection
    public record DecisionResult(boolean handled, String id) {
    }
}
