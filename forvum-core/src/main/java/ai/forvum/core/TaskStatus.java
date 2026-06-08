package ai.forvum.core;

/**
 * The lifecycle state of a background task, mirroring the {@code tasks.status} V2 column (ULTRAPLAN
 * section 7.2 P2-TASKLEDGER). A task is {@code PENDING} once scheduled, {@code RUNNING} while its turn
 * executes, then terminal {@code COMPLETED} on success or {@code ERROR} on failure. See {@link Role} for
 * the {@code dbValue}/{@code fromDbValue} convention this follows.
 */
public enum TaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    ERROR("error");

    private final String dbValue;

    TaskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TaskStatus fromDbValue(String value) {
        for (TaskStatus s : values()) {
            if (s.dbValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalStateException(
            "Unknown task status value from DB: '" + value + "'. Indicates schema drift or data "
          + "corruption. Check Flyway migrations and DB integrity.");
    }
}
