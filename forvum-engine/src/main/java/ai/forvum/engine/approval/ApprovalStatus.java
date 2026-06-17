package ai.forvum.engine.approval;

/**
 * Lifecycle state of a {@code tool_approvals} row (P2-14 #39). {@code PENDING} is written when a
 * confirm-required tool call is parked; the owner's decision (or a timeout) moves it to one terminal
 * state. The {@code dbValue} is the TEXT stored in {@code tool_approvals.status}; mirrors the
 * {@code dbValue}/{@code fromDbValue} convention of {@code InvocationStatus}/{@code TaskStatus}.
 */
public enum ApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    TIMED_OUT("timed_out");

    private final String dbValue;

    ApprovalStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ApprovalStatus fromDbValue(String value) {
        for (ApprovalStatus s : values()) {
            if (s.dbValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalStateException(
            "Unknown approval status value from DB: '" + value + "'. Indicates schema drift or data "
          + "corruption. Check Flyway migrations and DB integrity.");
    }
}
