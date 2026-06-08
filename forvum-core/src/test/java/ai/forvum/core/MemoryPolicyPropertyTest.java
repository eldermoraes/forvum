package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Property-style invariants for {@link MemoryPolicy} (mandatory per ULTRAPLAN section 10; DR-5). */
class MemoryPolicyPropertyTest {

    private static final long SEED = 20260608L;
    private static final int CASES = 200;

    @Test
    void defaultsMatchTheSettledContract() {
        MemoryPolicy d = MemoryPolicy.defaults();
        assertEquals(RetrievalStrategy.HYBRID, d.strategy());
        assertEquals(EnumSet.allOf(MemoryTier.class), d.tiers());
        assertEquals(8, d.topK());
        assertEquals(0.0, d.minScore());
        assertEquals(8000, d.compressThresholdChars());
    }

    @Test
    void tiersAreDefensivelyCopiedAndImmutable() {
        Set<MemoryTier> mutable = new HashSet<>(Set.of(MemoryTier.SEMANTIC));
        MemoryPolicy p = new MemoryPolicy(RetrievalStrategy.VECTOR, mutable, 4, 0.5, 0);
        mutable.add(MemoryTier.EPISODIC);                       // mutate the source after construction
        assertEquals(Set.of(MemoryTier.SEMANTIC), p.tiers());   // policy is unaffected
        assertThrows(UnsupportedOperationException.class, () -> p.tiers().add(MemoryTier.MESSAGES));
    }

    /** Valid (strategy, tiers, topK, minScore, compress) tuples: seeded-random, all within bounds. */
    static Stream<Arguments> validTuples() {
        Random r = new Random(SEED);
        return Stream.generate(() -> {
            RetrievalStrategy strategy = strategy(r);
            Set<MemoryTier> tiers = strategy == RetrievalStrategy.NONE && r.nextBoolean()
                ? EnumSet.noneOf(MemoryTier.class)   // empty legal only with NONE
                : nonEmptyTiers(r);
            int topK = 1 + r.nextInt(64);
            double minScore = r.nextDouble();        // [0, 1)
            int compress = r.nextInt(20_000);
            return arguments(strategy, tiers, topK, minScore, compress);
        }).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("validTuples")
    void validTupleConstructsAndExposesFields(
            RetrievalStrategy strategy, Set<MemoryTier> tiers, int topK, double minScore, int compress) {
        MemoryPolicy p = new MemoryPolicy(strategy, tiers, topK, minScore, compress);
        assertSame(strategy, p.strategy());
        assertEquals(Set.copyOf(tiers), p.tiers());
        assertEquals(topK, p.topK());
        assertEquals(minScore, p.minScore());
        assertEquals(compress, p.compressThresholdChars());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveTopK")
    void rejectsNonPositiveTopK(int topK) {
        assertThrows(IllegalStateException.class,
            () -> new MemoryPolicy(RetrievalStrategy.HYBRID, all(), topK, 0.0, 0));
    }

    static Stream<Integer> nonPositiveTopK() {
        Random r = new Random(SEED + 1);
        return Stream.concat(
            Stream.of(0, -1, Integer.MIN_VALUE),
            Stream.generate(() -> -1 - r.nextInt(1000)).limit(50));
    }

    @ParameterizedTest
    @MethodSource("outOfRangeScores")
    void rejectsOutOfRangeMinScore(double minScore) {
        assertThrows(IllegalStateException.class,
            () -> new MemoryPolicy(RetrievalStrategy.HYBRID, all(), 8, minScore, 0));
    }

    static Stream<Double> outOfRangeScores() {
        Random r = new Random(SEED + 2);
        Stream<Double> edges = Stream.of(-0.0001, 1.0001, -1.0, 2.0,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN);
        Stream<Double> randoms = Stream.generate(() ->
            r.nextBoolean() ? -r.nextDouble() - 0.0001 : 1.0001 + r.nextDouble()).limit(50);
        return Stream.concat(edges, randoms);
    }

    @Test
    void boundaryMinScoresAreAccepted() {
        assertEquals(0.0, new MemoryPolicy(RetrievalStrategy.VECTOR, all(), 1, 0.0, 0).minScore());
        assertEquals(1.0, new MemoryPolicy(RetrievalStrategy.VECTOR, all(), 1, 1.0, 0).minScore());
    }

    @Test
    void rejectsNegativeCompressThreshold() {
        assertThrows(IllegalStateException.class,
            () -> new MemoryPolicy(RetrievalStrategy.HYBRID, all(), 8, 0.0, -1));
    }

    @Test
    void rejectsNullStrategy() {
        assertThrows(IllegalStateException.class, () -> new MemoryPolicy(null, all(), 8, 0.0, 0));
    }

    @Test
    void rejectsNullTiers() {
        assertThrows(IllegalStateException.class,
            () -> new MemoryPolicy(RetrievalStrategy.HYBRID, null, 8, 0.0, 0));
    }

    /** Empty tiers are legal ONLY with strategy NONE (DR-5 DP-5). */
    @ParameterizedTest
    @EnumSource(value = RetrievalStrategy.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void emptyTiersRejectedForEveryStrategyButNone(RetrievalStrategy strategy) {
        assertThrows(IllegalStateException.class,
            () -> new MemoryPolicy(strategy, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 0));
    }

    @Test
    void emptyTiersAcceptedForNone() {
        MemoryPolicy p = new MemoryPolicy(RetrievalStrategy.NONE, EnumSet.noneOf(MemoryTier.class), 8, 0.0, 0);
        assertTrue(p.tiers().isEmpty());
    }

    private static Set<MemoryTier> all() {
        return EnumSet.allOf(MemoryTier.class);
    }

    private static RetrievalStrategy strategy(Random r) {
        RetrievalStrategy[] all = RetrievalStrategy.values();
        return all[r.nextInt(all.length)];
    }

    private static Set<MemoryTier> nonEmptyTiers(Random r) {
        MemoryTier[] all = MemoryTier.values();
        EnumSet<MemoryTier> set = EnumSet.noneOf(MemoryTier.class);
        do {
            for (MemoryTier t : all) {
                if (r.nextBoolean()) {
                    set.add(t);
                }
            }
        } while (set.isEmpty());
        return set;
    }
}
