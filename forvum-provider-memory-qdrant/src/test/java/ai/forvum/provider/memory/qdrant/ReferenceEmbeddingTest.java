package ai.forvum.provider.memory.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The deterministic reference embedding is stable, fixed-dimension, and L2-normalized — the property that
 * lets the hermetic tests build reproducible Qdrant requests with no external model.
 */
class ReferenceEmbeddingTest {

    private static final long SEED = 20260608L;

    @Test
    void embeddingHasTheDeclaredDimension() {
        assertEquals(ReferenceEmbedding.DIMENSION, ReferenceEmbedding.embed("hello world").size());
    }

    @ParameterizedTest
    @MethodSource("texts")
    void sameTextYieldsTheSameVector(String text) {
        assertEquals(ReferenceEmbedding.embed(text), ReferenceEmbedding.embed(text),
            "the reference embedding must be deterministic");
    }

    @ParameterizedTest
    @MethodSource("texts")
    void nonBlankTextIsL2Normalized(String text) {
        double norm = norm(ReferenceEmbedding.embed(text));
        assertTrue(Math.abs(norm - 1.0) < 1e-5,
            () -> "a non-blank text must produce a unit vector, got norm " + norm);
    }

    @Test
    void blankTextYieldsTheZeroVector() {
        for (String blank : new String[] {"", "   ", "!!!", "\t\n"}) {
            List<Float> v = ReferenceEmbedding.embed(blank);
            assertEquals(ReferenceEmbedding.DIMENSION, v.size());
            assertEquals(0.0, norm(v), 1e-9, () -> "blank/non-token text must embed to the zero vector");
        }
    }

    @Test
    void differentTextsGenerallyDifferAndAreCaseInsensitive() {
        // case-folding: "Hello" and "hello" share the same tokenization
        assertEquals(ReferenceEmbedding.embed("Hello"), ReferenceEmbedding.embed("hello"));
        // a clearly different token set produces a different vector
        assertTrue(!ReferenceEmbedding.embed("alpha").equals(ReferenceEmbedding.embed("zulu beta gamma")));
    }

    static Stream<String> texts() {
        Random r = new Random(SEED);
        Stream<String> edges = Stream.of("hello", "the quick brown fox", "qdrant", "a b c d e f g");
        Stream<String> randoms = Stream.generate(() -> randomText(r)).limit(40);
        return Stream.concat(edges, randoms);
    }

    private static String randomText(Random r) {
        int words = 1 + r.nextInt(8);
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < words; w++) {
            if (w > 0) {
                sb.append(' ');
            }
            int len = 1 + r.nextInt(8);
            for (int i = 0; i < len; i++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
        }
        return sb.toString();
    }

    private static double norm(List<Float> v) {
        double sum = 0.0;
        for (float f : v) {
            sum += (double) f * f;
        }
        return Math.sqrt(sum);
    }
}
