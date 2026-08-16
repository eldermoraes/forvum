package ai.forvum.tools.sessions;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionSummary;

import java.time.Instant;
import java.util.List;

/**
 * The {@code sessions.list} tool (#189): list the sessions visible to the caller's identity. Holds the
 * ToolSpec ({@link PermissionScope#SESSION_READ}) and renders the seam's summaries as a plain text list
 * for the model; the fail-closed identity scoping (a session owned by another identity is invisible) is
 * the engine's {@link SessionAccess} implementation.
 */
public final class SessionsListTool {

    /** D5 output bound (#189 audit): at most this many sessions re-enter the context window. */
    static final int MAX_SESSIONS = 50;

    public static final ToolSpec SPEC = new ToolSpec(
            "sessions.list",
            "List the conversation sessions visible to the current user, most recently active first. "
          + "Each line carries the session id to use with sessions.history / sessions.send.",
            PermissionScope.SESSION_READ,
            "{}");

    private SessionsListTool() {
    }

    static String list(SessionAccess sessions) {
        List<SessionSummary> visible = sessions.sessions();
        if (visible.isEmpty()) {
            return "No sessions are visible to you.";
        }
        StringBuilder out = new StringBuilder("Visible sessions:");
        int shown = Math.min(visible.size(), MAX_SESSIONS);
        for (SessionSummary session : visible.subList(0, shown)) {
            out.append("\n- ").append(session.id())
               .append(" (agent=").append(session.agentId())
               .append(", channel=").append(session.channelId())
               .append(", lastSeen=").append(Instant.ofEpochMilli(session.lastSeenAt()))
               .append(')');
        }
        if (visible.size() > shown) {
            out.append("\n[").append(visible.size() - shown)
               .append(" older sessions omitted — the list is capped at ").append(MAX_SESSIONS).append(']');
        }
        return out.toString();
    }
}
