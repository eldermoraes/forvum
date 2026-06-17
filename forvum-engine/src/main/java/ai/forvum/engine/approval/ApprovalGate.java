package ai.forvum.engine.approval;

import ai.forvum.core.id.AgentId;

/**
 * The narrow contract the {@code ToolExecutor} depends on to gate a {@code USER_CONFIRM_REQUIRED} tool
 * call on the owner's approval (P2-14 #39, ULTRAPLAN section 7.2 item 14 / section 9.1.b DP-9). It is the
 * THIRD gate, consulted only after the belt + RBAC scope gates have already permitted the call; the sole
 * production implementation is {@link ApprovalService}, which parks the call in the SQLite-backed
 * {@code tool_approvals} queue and blocks the turn until the owner approves/rejects or it times out.
 *
 * <p>Engine-internal (the executor enforces in-engine), so no Layer-3 plugin implements it; keeping it an
 * interface lets {@code ToolExecutor}'s unit tests exercise the gate with a simple double.
 */
public interface ApprovalGate {

    /**
     * Block until the {@code (sessionId, agentId, toolName, arguments)} tool call is approved or rejected.
     *
     * @return {@code true} to run the tool, {@code false} to deny it (an explicit reject, a timeout, or a
     *         non-interactive context with no approval surface)
     */
    boolean requireApproval(String sessionId, AgentId agentId, String toolName, String arguments);
}
