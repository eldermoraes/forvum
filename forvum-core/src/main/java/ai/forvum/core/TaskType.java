package ai.forvum.core;

/**
 * The kind of background task a {@code TaskRecord} describes, mirroring the {@code tasks.task_type} V2
 * column (ULTRAPLAN section 7.2 P2-TASKLEDGER). A {@code CRON} row is one scheduled-cron fire; a
 * {@code SUB_AGENT} row is one programmatic sub-agent spawn; {@code BACKGROUND} is the reserved
 * catch-all for other engine-initiated work. See {@link Role} for the {@code dbValue}/{@code fromDbValue}
 * convention this follows.
 */
public enum TaskType {
    CRON("cron"),
    SUB_AGENT("sub_agent"),
    BACKGROUND("background");

    private final String dbValue;

    TaskType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TaskType fromDbValue(String value) {
        for (TaskType t : values()) {
            if (t.dbValue.equals(value)) {
                return t;
            }
        }
        throw new IllegalStateException(
            "Unknown task type value from DB: '" + value + "'. Indicates schema drift or data "
          + "corruption. Check Flyway migrations and DB integrity.");
    }
}
