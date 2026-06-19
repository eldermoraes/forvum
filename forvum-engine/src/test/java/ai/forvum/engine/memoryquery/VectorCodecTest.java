package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Property-style round-trip tests for {@link VectorCodec} (P3-2, #50). No third-party property lib (CLAUDE.md §11). */
class VectorCodecTest {

    @Test
    void encodeNullIsNull() {
        assertNull(VectorCodec.encode(null));
    }

    @Test
    void decodeNullOrEmptyIsNull() {
        assertNull(VectorCodec.decode(null));
        assertNull(VectorCodec.decode(new byte[0]));
    }

    @Test
    void roundTripPreservesAVector() {
        float[] vector = {1.0f, -2.5f, 0.0f, 3.1415927f, Float.MAX_VALUE, Float.MIN_VALUE};
        assertArrayEquals(vector, VectorCodec.decode(VectorCodec.encode(vector)));
    }

    @Test
    void encodedLengthIsFourBytesPerFloat() {
        assertEquals(0, VectorCodec.encode(new float[0]).length);
        assertEquals(4 * 768, VectorCodec.encode(new float[768]).length);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 13, 27, 1, 2, 3}) // illegal lengths (not multiples of 4)
    void decodeRejectsAMalformedBlobLength(int extraBytes) {
        byte[] malformed = new byte[4 * 3 + (extraBytes % 4 == 0 ? 1 : extraBytes % 4)];
        assertThrows(IllegalArgumentException.class, () -> VectorCodec.decode(malformed));
    }

    @Test
    void seededRandomVectorsRoundTripExactly() {
        Random random = new Random(20260619L); // fixed seed: reproducible failures (CLAUDE.md §11)
        for (int trial = 0; trial < 200; trial++) {
            int dim = 1 + random.nextInt(1024);
            float[] vector = new float[dim];
            for (int i = 0; i < dim; i++) {
                vector[i] = random.nextFloat() * 200f - 100f;
            }
            assertArrayEquals(vector, VectorCodec.decode(VectorCodec.encode(vector)),
                    "round-trip must be exact for trial " + trial + " (dim " + dim + ")");
        }
    }

    @Test
    void edgeFloatsRoundTrip() {
        float[] edges = Stream.of(0.0f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
                .reduce(new float[0], VectorCodecTest::append, (a, b) -> b);
        byte[] blob = VectorCodec.encode(edges);
        float[] decoded = VectorCodec.decode(blob);
        // NaN != NaN under ==, so compare bit patterns.
        IntStream.range(0, edges.length).forEach(i ->
                assertEquals(Float.floatToRawIntBits(edges[i]), Float.floatToRawIntBits(decoded[i]),
                        "edge float at index " + i + " must round-trip bit-exact"));
    }

    private static float[] append(float[] array, float value) {
        float[] grown = java.util.Arrays.copyOf(array, array.length + 1);
        grown[array.length] = value;
        return grown;
    }
}
