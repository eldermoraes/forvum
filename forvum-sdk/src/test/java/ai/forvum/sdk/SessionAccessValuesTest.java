package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link SessionSummary} + {@link SessionMessage} canonical-constructor validation and accessor
 * round-trip (CLAUDE.md §11) — the #189 value records the {@link SessionAccess} seam returns.
 */
class SessionAccessValuesTest {

    @Test
    void summaryAccessorsRoundTrip() {
        SessionSummary s = new SessionSummary("web:sess-a", "main", "web", 10L, 20L);
        assertEquals("web:sess-a", s.id());
        assertEquals("main", s.agentId());
        assertEquals("web", s.channelId());
        assertEquals(10L, s.startedAt());
        assertEquals(20L, s.lastSeenAt());
    }

    @Test
    void summaryBlankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary(null, "main", "web", 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary(" ", "main", "web", 1L, 2L));
    }

    @Test
    void summaryBlankAgentIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary("s", null, "web", 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary("s", " ", "web", 1L, 2L));
    }

    @Test
    void summaryBlankChannelIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary("s", "main", null, 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> new SessionSummary("s", "main", " ", 1L, 2L));
    }

    @Test
    void messageAccessorsRoundTrip() {
        SessionMessage m = new SessionMessage("user", "hello", 5L);
        assertEquals("user", m.role());
        assertEquals("hello", m.content());
        assertEquals(5L, m.createdAt());
    }

    @Test
    void messageBlankRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionMessage(null, "hello", 1L));
        assertThrows(IllegalArgumentException.class, () -> new SessionMessage(" ", "hello", 1L));
    }

    @Test
    void messageNullContentIsRejectedButEmptyIsAllowed() {
        assertThrows(IllegalArgumentException.class, () -> new SessionMessage("user", null, 1L));
        assertEquals("", new SessionMessage("user", "", 1L).content());
    }
}
