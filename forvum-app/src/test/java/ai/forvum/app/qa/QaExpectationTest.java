package ai.forvum.app.qa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the three QA match modes and the fails-by-default malformed-mode behavior. */
class QaExpectationTest {

    @Test
    void exactMatchesOnlyWhenEqual() {
        assertTrue(new QaExpectation("exact", "echo: hi").satisfiedBy("echo: hi"));
        assertFalse(new QaExpectation("exact", "echo: hi").satisfiedBy("echo: hi there"));
        assertFalse(new QaExpectation("exact", "echo: hi").satisfiedBy("hi"));
    }

    @Test
    void containsMatchesASubstring() {
        assertTrue(new QaExpectation("contains", "brown fox").satisfiedBy("echo: the quick brown fox"));
        assertFalse(new QaExpectation("contains", "purple fox").satisfiedBy("echo: the quick brown fox"));
    }

    @Test
    void regexFindsAnywhereInTheReply() {
        assertTrue(new QaExpectation("regex", "^echo: \\w+$").satisfiedBy("echo: ping"));
        assertTrue(new QaExpectation("regex", "br\\w+n").satisfiedBy("echo: the quick brown fox"));
        assertFalse(new QaExpectation("regex", "^echo: \\w+$").satisfiedBy("echo: two words"));
    }

    @Test
    void caseInsensitiveModeAndTrimming() {
        assertTrue(new QaExpectation(" Exact ", "x").satisfiedBy("x"));
        assertTrue(new QaExpectation("CONTAINS", "x").satisfiedBy("axb"));
    }

    @Test
    void nullReplyIsTreatedAsEmptyNotAnError() {
        assertTrue(new QaExpectation("exact", "").satisfiedBy(null));
        assertFalse(new QaExpectation("contains", "x").satisfiedBy(null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "  ", "equals", "matches", "startsWith" })
    void unknownOrBlankModeThrowsSoAMalformedExpectationFailsByDefault(String mode) {
        assertThrows(IllegalArgumentException.class,
                () -> new QaExpectation(mode, "x").satisfiedBy("x"));
    }

    @Test
    void nullModeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new QaExpectation(null, "x").satisfiedBy("x"));
    }
}
