package ai.forvum.core;

/**
 * Conversation role, mirroring the {@code messages.role} V1 CHECK constraint (ULTRAPLAN section 4.2).
 *
 * <p>Each constant carries its DB-literal string; {@link #dbValue()} exposes it to persistence
 * adapters and {@link #fromDbValue(String)} parses it back from JDBC. Changing a CHECK constraint
 * requires a coordinated update here plus a forward-only Flyway migration.
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String dbValue;

    Role(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Role fromDbValue(String value) {
        for (Role r : values()) {
            if (r.dbValue.equals(value)) {
                return r;
            }
        }
        throw new IllegalStateException(
            "Unknown role value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}
