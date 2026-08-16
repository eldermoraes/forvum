package ai.forvum.sdk;

/**
 * One caller-visible session as surfaced by the {@link SessionAccess} seam (#189) — a projection of the
 * engine's {@code sessions} row for the model-facing {@code sessions.list} tool. JDK types only, so the
 * tool module carries no persistence dependency and this record stays reflection-free and native-safe
 * (it is rendered to text for the model, never JSON-serialized).
 *
 * @param id         the session id (the engine keys channel sessions {@code channelId:nativeUserId})
 * @param agentId    the agent the session's turns run as
 * @param channelId  the channel the session arrived on
 * @param startedAt  epoch millis of the session's first turn
 * @param lastSeenAt epoch millis of the session's most recent turn
 */
public record SessionSummary(String id, String agentId, String channelId, long startedAt, long lastSeenAt) {

    public SessionSummary {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SessionSummary id must be non-null and non-blank.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("SessionSummary agentId must be non-null and non-blank.");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("SessionSummary channelId must be non-null and non-blank.");
        }
    }
}
