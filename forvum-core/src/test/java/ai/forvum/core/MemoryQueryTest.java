package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Construction/validation invariants for {@link MemoryQuery} (DR-5). */
class MemoryQueryTest {

    @Test
    void constructsAndExposesFields() {
        MemoryQuery q = new MemoryQuery("agent-1", "sess-7", "what did we decide?");
        assertEquals("agent-1", q.agentId());
        assertEquals("sess-7", q.sessionId());
        assertEquals("what did we decide?", q.text());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsBlankAgentId(String agentId) {
        assertThrows(IllegalStateException.class, () -> new MemoryQuery(agentId, "s", "t"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsBlankSessionId(String sessionId) {
        assertThrows(IllegalStateException.class, () -> new MemoryQuery("a", sessionId, "t"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void rejectsBlankText(String text) {
        assertThrows(IllegalStateException.class, () -> new MemoryQuery("a", "s", text));
    }
}
