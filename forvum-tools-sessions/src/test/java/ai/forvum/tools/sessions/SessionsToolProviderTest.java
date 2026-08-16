package ai.forvum.tools.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.SessionAccess;
import ai.forvum.sdk.SessionMessage;
import ai.forvum.sdk.SessionSummary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * {@link SessionsToolProvider} contract (#189): it contributes {@code agents.list} / {@code sessions.list} /
 * {@code sessions.history} (SESSION_READ) and {@code sessions.send} (SESSION_WRITE), self-dispatches each by
 * name to the injected {@link SessionAccess} seam, formats the result for the model, and rejects an unknown
 * tool or a missing argument. A pure unit test with a fake {@code SessionAccess} — the seam's engine wiring
 * (identity scoping, fail-closed denial, turn dispatch) is proven by {@code EngineSessionAccessIT}.
 */
class SessionsToolProviderTest {

    private SessionsToolProvider providerWith(FakeSessionAccess sessions) {
        SessionsToolProvider provider = new SessionsToolProvider();
        provider.sessions = sessions;
        return provider;
    }

    @Test
    void reportsTheSessionsExtensionId() {
        assertEquals("sessions", providerWith(new FakeSessionAccess()).extensionId());
    }

    @Test
    void contributesTheFourToolsWithReadAndWriteSeparatelyScoped() {
        List<ToolSpec> tools = providerWith(new FakeSessionAccess()).tools();
        assertEquals(4, tools.size());

        for (String reader : List.of("agents.list", "sessions.list", "sessions.history")) {
            ToolSpec spec = tools.stream().filter(t -> t.name().equals(reader)).findFirst().orElseThrow();
            assertEquals(PermissionScope.SESSION_READ, spec.requiredScope(),
                    reader + " is gated by the read half of the session scopes");
            assertFalse(spec.userConfirmRequired(), reader + " is a read — no approval gate");
        }

        ToolSpec send = tools.stream().filter(t -> t.name().equals("sessions.send")).findFirst().orElseThrow();
        assertEquals(PermissionScope.SESSION_WRITE, send.requiredScope(),
                "sessions.send is gated by the write half, so a role can grant reads without delivery");
    }

    @Test
    void agentsListRendersTheConfiguredIds() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.agentIds = List.of("main", "researcher");

        String result = providerWith(sessions).invoke("agents.list", Map.of());

        assertTrue(result.contains("- main") && result.contains("- researcher"),
                "every configured agent id is rendered for the model");
    }

    @Test
    void agentsListReportsWhenNothingIsVisible() {
        String result = providerWith(new FakeSessionAccess()).invoke("agents.list", Map.of());
        assertTrue(result.toLowerCase().contains("no agents"),
                "an empty (fail-closed) read tells the model nothing is visible, not an empty string");
    }

    @Test
    void sessionsListRendersTheVisibleSessions() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.sessions = List.of(new SessionSummary("web:sess-a", "main", "web", 1L, 2L));

        String result = providerWith(sessions).invoke("sessions.list", Map.of());

        assertTrue(result.contains("web:sess-a"), "the session id is rendered — it keys history/send");
        assertTrue(result.contains("agent=main") && result.contains("channel=web"));
    }

    @Test
    void sessionsListIsCappedAtMaxSessionsWithAnOmissionMarker() {
        // D5 (#189 audit): the rendered list re-enters the caller's context window — bound it.
        FakeSessionAccess sessions = new FakeSessionAccess();
        List<SessionSummary> many = new java.util.ArrayList<>();
        for (int i = 0; i < SessionsListTool.MAX_SESSIONS + 7; i++) {
            many.add(new SessionSummary("web:s" + i, "main", "web", 1L, 2L));
        }
        sessions.sessions = List.copyOf(many);

        String result = providerWith(sessions).invoke("sessions.list", Map.of());

        assertTrue(result.contains("web:s" + (SessionsListTool.MAX_SESSIONS - 1)),
                "the newest MAX_SESSIONS entries are rendered");
        assertFalse(result.contains("web:s" + SessionsListTool.MAX_SESSIONS + " "),
                "entries past the cap are omitted");
        assertTrue(result.contains("7 older sessions omitted"),
                "the cut is marked so the model knows the list is bounded");
    }

    @Test
    void sessionsListReportsWhenNothingIsVisible() {
        String result = providerWith(new FakeSessionAccess()).invoke("sessions.list", Map.of());
        assertTrue(result.toLowerCase().contains("no sessions"),
                "an empty (fail-closed) read tells the model nothing is visible");
    }

    @Test
    void historyDispatchesToTheSeamAndFormatsOldestFirst() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.history = List.of(
                new SessionMessage("user", "hello", 1L),
                new SessionMessage("assistant", "pong", 2L));

        String result = providerWith(sessions).invoke("sessions.history",
                Map.of("sessionId", "web:sess-a", "limit", 5));

        assertEquals("web:sess-a", sessions.lastHistorySessionId, "the session id reaches the seam");
        assertEquals(5, sessions.lastHistoryLimit, "the model's limit reaches the seam");
        assertTrue(result.indexOf("user: hello") < result.indexOf("assistant: pong"),
                "the transcript is rendered oldest first");
    }

    @Test
    void historyDefaultsAndClampsTheLimit() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        SessionsToolProvider provider = providerWith(sessions);

        provider.invoke("sessions.history", Map.of("sessionId", "s"));
        assertEquals(SessionsHistoryTool.DEFAULT_LIMIT, sessions.lastHistoryLimit,
                "a missing limit defaults to " + SessionsHistoryTool.DEFAULT_LIMIT);

        provider.invoke("sessions.history", Map.of("sessionId", "s", "limit", 100000));
        assertEquals(SessionsHistoryTool.MAX_LIMIT, sessions.lastHistoryLimit,
                "an oversized limit is clamped — the transcript re-enters the context window");

        provider.invoke("sessions.history", Map.of("sessionId", "s", "limit", "7"));
        assertEquals(7, sessions.lastHistoryLimit, "a numeric-string limit (model JSON quirk) is accepted");
    }

    @Test
    void historyTruncatesAnOversizedMessageSegmentWithTheMarker() {
        // D5 (#189 audit): one oversized transcript message must not blow the caller's window.
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.history = List.of(
                new SessionMessage("assistant", "y".repeat(SessionsHistoryTool.MAX_CONTENT_CHARS + 100), 1L));

        String result = providerWith(sessions).invoke("sessions.history", Map.of("sessionId", "s"));

        assertTrue(result.contains(SessionsHistoryTool.TRUNCATION_MARKER),
                "the cut segment is marked");
        assertFalse(result.contains("y".repeat(SessionsHistoryTool.MAX_CONTENT_CHARS + 1)),
                "no segment exceeds the per-message bound");
    }

    @Test
    void historyReportsAnEmptyTranscript() {
        String result = providerWith(new FakeSessionAccess()).invoke("sessions.history",
                Map.of("sessionId", "web:sess-a"));
        assertTrue(result.toLowerCase().contains("no messages"),
                "an empty transcript tells the model the session has no messages yet");
    }

    @Test
    void sendDispatchesToTheSeamAndRendersTheReply() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.sendReply = "pong";

        String result = providerWith(sessions).invoke("sessions.send",
                Map.of("sessionId", "web:sess-a", "message", "ping"));

        assertEquals("web:sess-a", sessions.lastSendSessionId, "the target session reaches the seam");
        assertEquals("ping", sessions.lastSendMessage, "the message reaches the seam");
        assertTrue(result.contains("pong"), "the target agent's reply is rendered for the model");
    }

    @Test
    void sendTruncatesAnOversizedRelayedReplyWithTheMarker() {
        // D5 (#189 audit): the target agent's reply re-enters the CALLER's window — bound it.
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.sendReply = "z".repeat(SessionsSendTool.MAX_REPLY_CHARS + 500);

        String result = providerWith(sessions).invoke("sessions.send",
                Map.of("sessionId", "s", "message", "hi"));

        assertTrue(result.contains(SessionsSendTool.TRUNCATION_MARKER), "the cut reply is marked");
        assertFalse(result.contains("z".repeat(SessionsSendTool.MAX_REPLY_CHARS + 1)),
                "the relayed reply never exceeds the bound");
    }

    @Test
    void sendPropagatesTheSeamsDenial() {
        FakeSessionAccess sessions = new FakeSessionAccess();
        sessions.denySends = true;

        assertThrows(IllegalArgumentException.class,
                () -> providerWith(sessions).invoke("sessions.send",
                        Map.of("sessionId", "other:sess", "message", "hi")),
                "a fail-closed seam denial (invisible session) propagates as the tool error");
    }

    @Test
    void invokeRejectsAnUnknownToolName() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(new FakeSessionAccess()).invoke("sessions.delete", Map.of()));
    }

    @Test
    void invokeRejectsAMissingOrBlankRequiredArgument() {
        SessionsToolProvider provider = providerWith(new FakeSessionAccess());
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("sessions.history", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("sessions.send", Map.of("sessionId", "s", "message", " ")));
    }

    @Test
    void invokeRejectsANonNumericLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> providerWith(new FakeSessionAccess()).invoke("sessions.history",
                        Map.of("sessionId", "s", "limit", "many")));
    }

    /** A deterministic in-memory {@link SessionAccess} double — no engine, no SQLite, no turn. */
    static final class FakeSessionAccess implements SessionAccess {
        List<String> agentIds = List.of();
        List<SessionSummary> sessions = List.of();
        List<SessionMessage> history = List.of();
        String sendReply = "";
        boolean denySends = false;
        String lastHistorySessionId;
        int lastHistoryLimit;
        String lastSendSessionId;
        String lastSendMessage;

        @Override
        public List<String> agentIds() {
            return agentIds;
        }

        @Override
        public List<SessionSummary> sessions() {
            return sessions;
        }

        @Override
        public List<SessionMessage> history(String sessionId, int limit) {
            lastHistorySessionId = sessionId;
            lastHistoryLimit = limit;
            return history;
        }

        @Override
        public String send(String sessionId, String message) {
            if (denySends) {
                throw new IllegalArgumentException("No session '" + sessionId + "' is visible to the caller.");
            }
            lastSendSessionId = sessionId;
            lastSendMessage = message;
            return sendReply;
        }
    }
}
