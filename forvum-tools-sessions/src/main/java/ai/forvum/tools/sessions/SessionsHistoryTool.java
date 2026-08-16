package ai.forvum.tools.sessions;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionMessage;

import java.util.List;

/**
 * The {@code sessions.history} tool (#189): read the recent transcript of a caller-visible session.
 * Holds the ToolSpec ({@link PermissionScope#SESSION_READ}) and renders the seam's messages as
 * {@code role: content} lines for the model; the fail-closed visibility check (an invisible session and
 * a nonexistent one fail identically) is the engine's {@link SessionAccess} implementation.
 */
public final class SessionsHistoryTool {

    /** Default transcript window when the model supplies no {@code limit}. */
    static final int DEFAULT_LIMIT = 20;

    /** Upper bound on {@code limit} — a transcript re-entering the context window must stay bounded (D5). */
    static final int MAX_LIMIT = 50;

    /** D5 per-message bound: a single oversized transcript message must not blow the caller's window. */
    static final int MAX_CONTENT_CHARS = 500;

    /** Fixed marker appended to a truncated message segment (D5 — a literal, never config). */
    static final String TRUNCATION_MARKER = " [truncated by sessions.history]";

    public static final ToolSpec SPEC = new ToolSpec(
            "sessions.history",
            "Read the most recent messages of a session visible to the current user (oldest first). "
          + "Use sessions.list to discover session ids.",
            PermissionScope.SESSION_READ,
            "{\"type\":\"object\",\"properties\":{"
          + "\"sessionId\":{\"type\":\"string\",\"description\":\"the session id, from sessions.list\"},"
          + "\"limit\":{\"type\":\"integer\",\"description\":\"max messages to return (default 20, max 50)\"}},"
          + "\"required\":[\"sessionId\"]}");

    private SessionsHistoryTool() {
    }

    static String history(SessionAccess sessions, String sessionId, int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIMIT);
        List<SessionMessage> messages = sessions.history(sessionId, bounded);
        if (messages.isEmpty()) {
            return "Session '" + sessionId + "' has no messages yet.";
        }
        StringBuilder out = new StringBuilder("History of session '").append(sessionId).append("':");
        for (SessionMessage message : messages) {
            out.append('\n').append(message.role()).append(": ").append(bounded(message.content()));
        }
        return out.toString();
    }

    /** Truncate one transcript segment to {@link #MAX_CONTENT_CHARS}, marking the cut (D5). */
    static String bounded(String content) {
        if (content == null || content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + TRUNCATION_MARKER;
    }
}
