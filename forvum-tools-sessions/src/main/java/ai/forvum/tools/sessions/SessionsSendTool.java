package ai.forvum.tools.sessions;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.SessionAccess;

/**
 * The {@code sessions.send} tool (#189): deliver a message into another caller-visible session by
 * dispatching a full turn through the engine's turn driver (the target session's agent responds and the
 * exchange is appended to the target transcript). Holds the ToolSpec
 * ({@link PermissionScope#SESSION_WRITE}); the fail-closed cross-identity denial and the delivery-loop
 * guard are the engine's {@link SessionAccess} implementation.
 */
public final class SessionsSendTool {

    public static final ToolSpec SPEC = new ToolSpec(
            "sessions.send",
            "Send a message into another session visible to the current user; the target session's agent "
          + "processes it as a turn and its reply is returned. Use sessions.list to discover session ids.",
            PermissionScope.SESSION_WRITE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"sessionId\":{\"type\":\"string\",\"description\":\"the target session id, from sessions.list\"},"
          + "\"message\":{\"type\":\"string\",\"description\":\"the message to deliver into the target session\"}},"
          + "\"required\":[\"sessionId\",\"message\"]}");

    /** D5 output bound (#189 audit): the relayed reply re-entering the caller's window stays bounded. */
    static final int MAX_REPLY_CHARS = 2000;

    /** Fixed marker appended to a truncated relayed reply (D5 — a literal, never config). */
    static final String TRUNCATION_MARKER = " [reply truncated by sessions.send]";

    private SessionsSendTool() {
    }

    static String send(SessionAccess sessions, String sessionId, String message) {
        String reply = sessions.send(sessionId, message);
        if (reply != null && reply.length() > MAX_REPLY_CHARS) {
            reply = reply.substring(0, MAX_REPLY_CHARS) + TRUNCATION_MARKER;
        }
        return "Delivered into session '" + sessionId + "'. Reply: " + reply;
    }
}
