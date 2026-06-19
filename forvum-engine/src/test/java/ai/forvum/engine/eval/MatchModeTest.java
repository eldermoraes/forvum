package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link MatchMode} matching + wire parsing (P3-10 #58). */
class MatchModeTest {

    @ParameterizedTest
    @CsvSource({
            "contains,CONTAINS",
            "CONTAINS,CONTAINS",
            "Exact,EXACT",
            "  regex ,REGEX",
    })
    void fromWireParsesEachModeCaseInsensitively(String token, MatchMode expected) {
        assertEquals(expected, MatchMode.fromWire(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "fuzzy", "matches"})
    void fromWireRejectsBlankOrUnknown(String token) {
        assertThrows(IllegalStateException.class, () -> MatchMode.fromWire(token));
    }

    @Test
    void fromWireRejectsNull() {
        assertThrows(IllegalStateException.class, () -> MatchMode.fromWire(null));
    }

    @Test
    void containsIsCaseInsensitiveSubstring() {
        assertTrue(MatchMode.CONTAINS.matches("pong", "PONG!"));
        assertTrue(MatchMode.CONTAINS.matches("HELLO", "well hello there"));
        assertFalse(MatchMode.CONTAINS.matches("pong", "ping"));
    }

    @Test
    void exactIsTrimmedCaseInsensitiveEquality() {
        assertTrue(MatchMode.EXACT.matches("pong", "  PONG  "));
        assertFalse(MatchMode.EXACT.matches("pong", "pong!"));
    }

    @Test
    void regexSearchesAnywhere() {
        assertTrue(MatchMode.REGEX.matches("\\d{3}", "code 404 returned"));
        assertFalse(MatchMode.REGEX.matches("^yes$", "yes sir"));
    }

    @Test
    void regexRejectsAnInvalidPattern() {
        assertThrows(IllegalStateException.class, () -> MatchMode.REGEX.matches("(", "anything"));
    }
}
