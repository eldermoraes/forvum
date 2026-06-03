package ai.forvum.core;

/**
 * Tool-invocation outcome, mirroring the {@code tool_invocations.status} V1 CHECK constraint
 * (ULTRAPLAN section 4.2). Resolves the forward reference declared by {@code ToolResult.status}
 * (section 4.3.2). See {@link Role} for the {@code dbValue}/{@code fromDbValue} convention.
 */
public enum InvocationStatus {
    OK("ok"),
    ERROR("error"),
    DENIED("denied");

    private final String dbValue;

    InvocationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static InvocationStatus fromDbValue(String value) {
        for (InvocationStatus s : values()) {
            if (s.dbValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalStateException(
            "Unknown invocation status value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}
