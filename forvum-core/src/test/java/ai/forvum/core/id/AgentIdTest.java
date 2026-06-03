package ai.forvum.core.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link AgentId} value object: non-blank token, no edge whitespace; {@code toString} is the raw value. */
class AgentIdTest {

    @Test
    void acceptsValidTokenAndExposesValue() {
        assertEquals("assistant", new AgentId("assistant").value());
    }

    @Test
    void toStringReturnsRawValue() {
        assertEquals("assistant", new AgentId("assistant").toString());
    }

    @Test
    void rejectsNullBlankAndEdgeWhitespace() {
        assertThrows(IllegalStateException.class, () -> new AgentId(null));
        assertThrows(IllegalStateException.class, () -> new AgentId(""));
        assertThrows(IllegalStateException.class, () -> new AgentId("  "));
        assertThrows(IllegalStateException.class, () -> new AgentId(" assistant"));
        assertThrows(IllegalStateException.class, () -> new AgentId("assistant "));
    }
}
