package ai.forvum.engine.approval;

/**
 * A read-only view of one {@code pending} {@code tool_approvals} row (P2-14 #39), surfaced by
 * {@link ApprovalService#listPending()} for the web approval dashboard. Built from a JDBC/Panache row and
 * displayed, never JSON-serialized by the engine (the Layer-4 dashboard route maps it to its own
 * {@code @RegisterForReflection} view), so it carries no reflection registration — mirroring the
 * {@code doctor}/replay view records.
 */
public record PendingApproval(String id, String sessionId, String agentId, String toolName,
                              String arguments, long createdAt) {
}
