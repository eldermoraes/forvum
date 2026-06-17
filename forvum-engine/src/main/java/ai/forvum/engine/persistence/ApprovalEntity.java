package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the {@code tool_approvals} table (V4) — the user-approval queue for {@code USER_CONFIRM_REQUIRED}
 * tool calls (P2-14 #39, ULTRAPLAN section 7.2 item 14 / section 9.1.b DP-9). One row per parked
 * invocation; the TEXT primary key is a UUID string supplied by the {@code ApprovalService}.
 * {@code status} holds the {@code ApprovalStatus} {@code dbValue}
 * ({@code pending|approved|rejected|timed_out}). {@code userMessage} carries the original turn prompt so an
 * approval orphaned by a process restart can re-dispatch the turn (R1). The terminal audit of the resolved
 * call rides {@code tool_invocations}; this table owns only the approval lifecycle.
 */
@Entity
@Table(name = "tool_approvals")
public class ApprovalEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public String id;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    @Column(name = "arguments", nullable = false)
    public String arguments;

    @Column(name = "user_message")
    public String userMessage;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "decision_reason")
    public String decisionReason;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "resolved_at")
    public Long resolvedAt;
}
