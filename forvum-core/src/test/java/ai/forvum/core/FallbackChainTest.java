package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Property-style + example checks for {@link FallbackChain} (DR-4c [DP-3], the §10 parser/record
 * mandate): null/empty/duplicate matrices, {@link FallbackChain#links()} order, and
 * {@link FallbackChain#single(ModelRef)} equivalence — over curated edge cases plus seeded-random inputs.
 */
class FallbackChainTest {

    private static ModelRef ref(int i) {
        return new ModelRef("p" + i, "m" + i);
    }

    @Test
    void linksIsPrimaryThenFallbacksInOrder() {
        FallbackChain chain = new FallbackChain(ref(0), List.of(ref(1), ref(2)));
        assertEquals(List.of(ref(0), ref(1), ref(2)), chain.links());
    }

    @Test
    void singleHasOneLinkAndEmptyFallbacks() {
        FallbackChain chain = FallbackChain.single(ref(0));
        assertEquals(List.of(ref(0)), chain.links());
        assertTrue(chain.fallbacks().isEmpty());
    }

    @Test
    void emptyFallbacksIsAllowed() {
        FallbackChain chain = new FallbackChain(ref(0), List.of());
        assertEquals(List.of(ref(0)), chain.links());
    }

    @Test
    void nullPrimaryRejected() {
        assertThrows(IllegalStateException.class, () -> new FallbackChain(null, List.of()));
    }

    @Test
    void nullFallbacksListRejected() {
        assertThrows(IllegalStateException.class, () -> new FallbackChain(ref(0), null));
    }

    @Test
    void nullFallbackElementRejected() {
        List<ModelRef> fallbacks = new ArrayList<>();
        fallbacks.add(ref(1));
        fallbacks.add(null);
        assertThrows(IllegalStateException.class, () -> new FallbackChain(ref(0), fallbacks));
    }

    @Test
    void primaryRepeatedInFallbacksRejected() {
        assertThrows(IllegalStateException.class,
                () -> new FallbackChain(ref(0), List.of(ref(1), ref(0))));
    }

    @Test
    void duplicateWithinFallbacksRejected() {
        assertThrows(IllegalStateException.class,
                () -> new FallbackChain(ref(0), List.of(ref(1), ref(1))));
    }

    @Test
    void fallbacksIsDefensivelyCopiedImmutable() {
        List<ModelRef> mutable = new ArrayList<>(List.of(ref(1)));
        FallbackChain chain = new FallbackChain(ref(0), mutable);
        mutable.add(ref(2));
        assertEquals(List.of(ref(1)), chain.fallbacks());
        assertThrows(UnsupportedOperationException.class, () -> chain.fallbacks().add(ref(3)));
        assertThrows(UnsupportedOperationException.class, () -> chain.links().add(ref(3)));
    }

    @Test
    void singleEquivalentToTwoArgWithEmptyFallbacks() {
        assertEquals(new FallbackChain(ref(0), List.of()), FallbackChain.single(ref(0)));
    }

    /** A distinct-ref chain of any length round-trips: links() == primary followed by the fallbacks. */
    @ParameterizedTest
    @MethodSource("seededDistinctChains")
    void linksRoundTripsForDistinctChains(List<ModelRef> distinct) {
        ModelRef primary = distinct.get(0);
        List<ModelRef> fallbacks = distinct.subList(1, distinct.size());
        FallbackChain chain = new FallbackChain(primary, fallbacks);

        List<ModelRef> expected = new ArrayList<>();
        expected.add(primary);
        expected.addAll(fallbacks);
        assertEquals(expected, chain.links());
        // links() head is always the operator-preference primary.
        assertSame(primary, chain.links().get(0));
    }

    /** Injecting one repeat into any seeded distinct chain is always rejected. */
    @ParameterizedTest
    @MethodSource("seededDistinctChains")
    void anyRepeatIsRejected(List<ModelRef> distinct) {
        List<ModelRef> withRepeat = new ArrayList<>(distinct);
        withRepeat.add(distinct.get(0)); // repeat the primary at the tail
        assertThrows(IllegalStateException.class,
                () -> new FallbackChain(distinct.get(0), withRepeat.subList(1, withRepeat.size())));
    }

    static Stream<List<ModelRef>> seededDistinctChains() {
        Random rng = new Random(4252L); // fixed seed → reproducible failures (§11)
        List<List<ModelRef>> cases = new ArrayList<>();
        cases.add(List.of(ref(0)));                       // single
        cases.add(List.of(ref(0), ref(1)));               // one fallback
        cases.add(Arrays.asList(ref(0), ref(1), ref(2), ref(3))); // several
        for (int c = 0; c < 8; c++) {
            int len = 1 + rng.nextInt(5);
            List<ModelRef> chain = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                chain.add(new ModelRef("prov" + c, "model" + c + "-" + i));
            }
            cases.add(chain);
        }
        return cases.stream();
    }
}
