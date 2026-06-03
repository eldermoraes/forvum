package ai.forvum.core;

/**
 * Episodic-memory event kind, mirroring the {@code episodic_memory.event_type} V1 CHECK constraint
 * (ULTRAPLAN section 4.2). See {@link Role} for the {@code dbValue}/{@code fromDbValue} convention.
 */
public enum EventType {
    OBSERVATION("observation"),
    DECISION("decision"),
    REFLECTION("reflection");

    private final String dbValue;

    EventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static EventType fromDbValue(String value) {
        for (EventType e : values()) {
            if (e.dbValue.equals(value)) {
                return e;
            }
        }
        throw new IllegalStateException(
            "Unknown event_type value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}
