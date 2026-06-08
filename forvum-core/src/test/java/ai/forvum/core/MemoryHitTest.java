package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Construction/validation invariants for {@link MemoryHit} (DR-5). */
class MemoryHitTest {

    private static final long SEED = 20260608L;

    @Test
    void constructsAndExposesFields() {
        MemoryHit h = new MemoryHit(MemoryTier.SEMANTIC, "a fact", 0.73, "semantic_memory#5");
        assertEquals(MemoryTier.SEMANTIC, h.tier());
        assertEquals("a fact", h.content());
        assertEquals(0.73, h.score());
        assertEquals("semantic_memory#5", h.source());
    }

    @Test
    void allowsEmptyContentAndEmptySource() {
        MemoryHit h = new MemoryHit(MemoryTier.MESSAGES, "", 0.0, "");
        assertEquals("", h.content());
        assertEquals("", h.source());
    }

    @Test
    void rejectsNullTier() {
        assertThrows(IllegalStateException.class, () -> new MemoryHit(null, "c", 0.5, "s"));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalStateException.class, () -> new MemoryHit(MemoryTier.SEMANTIC, null, 0.5, "s"));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalStateException.class, () -> new MemoryHit(MemoryTier.SEMANTIC, "c", 0.5, null));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0001, 1.0001, -1.0, 2.0, Double.NaN,
        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsOutOfRangeScore(double score) {
        assertThrows(IllegalStateException.class,
            () -> new MemoryHit(MemoryTier.SEMANTIC, "c", score, "s"));
    }

    @ParameterizedTest
    @MethodSource("inRangeScores")
    void acceptsScoresInRange(double score) {
        assertEquals(score, new MemoryHit(MemoryTier.EPISODIC, "c", score, "s").score());
    }

    static Stream<Arguments> inRangeScores() {
        Random r = new Random(SEED);
        Stream<Arguments> edges = Stream.of(arguments(0.0), arguments(1.0));
        Stream<Arguments> randoms = Stream.generate(() -> arguments(r.nextDouble())).limit(50);
        return Stream.concat(edges, randoms);
    }
}
