package ai.forvum.engine.replay;

import java.util.List;

/**
 * The result of replaying a session: its header (agent/channel/identity/start time, from the
 * {@code sessions} row) and the ordered {@link ReplaySegment}s. {@code found} is false when no
 * {@code sessions} row matches the requested id — distinct from a real but empty session ({@code found}
 * true, no segments).
 */
public record ReplaySession(
        String sessionId,
        boolean found,
        String agentId,
        String channelId,
        String identityId,
        long startedAt,
        List<ReplaySegment> segments) {

    public ReplaySession {
        segments = List.copyOf(segments);
    }

    /** A session id that matched no {@code sessions} row. */
    static ReplaySession notFound(String sessionId) {
        return new ReplaySession(sessionId, false, null, null, null, 0L, List.of());
    }

    /** Count of conversational messages in the replay (excludes tool invocations). */
    public long messageCount() {
        return segments.stream().filter(MessageSegment.class::isInstance).count();
    }

    /** Count of tool invocations in the replay. */
    public long toolCount() {
        return segments.stream().filter(ToolSegment.class::isInstance).count();
    }
}
