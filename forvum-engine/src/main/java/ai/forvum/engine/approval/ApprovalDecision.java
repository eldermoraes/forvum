package ai.forvum.engine.approval;

/**
 * The owner's resolution of a parked confirm-required tool call (P2-14 #39): approve or reject, with an
 * optional human-supplied reason carried into the {@code tool_approvals} audit row. The value a blocked
 * {@code ApprovalService.requireApproval} receives through its in-memory future when the web dashboard
 * decides. Engine-internal, never JSON-serialized, so it carries no reflection registration.
 */
public record ApprovalDecision(boolean approved, String reason) {
}
