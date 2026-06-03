package ai.forvum.core.budget;

/** A window spanning a single session, filtered by the {@code (sessionId, agentId)} pair. No internal reset. */
public record SessionWindow(String sessionId, String agentId) implements Window {
    public SessionWindow {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                "SessionWindow sessionId must be non-null and non-blank. "
              + "Got: '" + sessionId + "'. This indicates the session "
              + "context was not propagated at construction time.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException(
                "SessionWindow agentId must be non-null and non-blank. "
              + "Got: '" + agentId + "'. Check agent resolution before "
              + "constructing.");
        }
    }
}
