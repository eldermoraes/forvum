package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link CycleSpec} structural shape (DR-8 DP-7): a non-empty ordered list of instruction strings, a
 * round cap {@code >= 1}, and an optional stop sentinel (blank normalized to null). Plain Surefire — no
 * Quarkus boot.
 */
class CycleSpecTest {

    @Test
    void acceptsAValidCycle() {
        CycleSpec c = new CycleSpec(List.of("reflect", "critique", "revise"), 3, "DONE");
        assertEquals(List.of("reflect", "critique", "revise"), c.steps());
        assertEquals(3, c.maxRounds());
        assertEquals("DONE", c.stopSentinel());
    }

    @Test
    void acceptsANullStopSentinel() {
        CycleSpec c = new CycleSpec(List.of("reflect"), 1, null);
        assertNull(c.stopSentinel());
    }

    @Test
    void normalizesABlankStopSentinelToNull() {
        CycleSpec c = new CycleSpec(List.of("reflect"), 1, "   ");
        assertNull(c.stopSentinel(), "a blank sentinel is treated as absent (rounds-only termination)");
    }

    @Test
    void stepsAreDefensivelyCopiedAndImmutable() {
        List<String> source = new ArrayList<>();
        source.add("reflect");
        CycleSpec c = new CycleSpec(source, 2, null);
        source.add("revise");
        assertEquals(1, c.steps().size(), "steps must be defensively copied");
        assertThrows(UnsupportedOperationException.class, () -> c.steps().add("x"));
    }

    @Test
    void rejectsEmptySteps() {
        assertThrows(IllegalStateException.class, () -> new CycleSpec(List.of(), 3, null));
    }

    @Test
    void rejectsNullSteps() {
        assertThrows(IllegalStateException.class, () -> new CycleSpec(null, 3, null));
    }

    @Test
    void rejectsABlankStepEntry() {
        assertThrows(IllegalStateException.class,
                () -> new CycleSpec(Arrays.asList("reflect", "  "), 3, null));
    }

    @Test
    void rejectsANullStepEntry() {
        assertThrows(IllegalStateException.class,
                () -> new CycleSpec(Arrays.asList("reflect", null), 3, null));
    }

    @Test
    void rejectsMaxRoundsBelowOne() {
        assertThrows(IllegalStateException.class, () -> new CycleSpec(List.of("reflect"), 0, null));
    }
}
