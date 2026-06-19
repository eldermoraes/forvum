package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

/** Property-style tests for {@link VectorMath#cosine} (P3-2, #50). */
class VectorMathTest {

    private static final double EPS = 1e-6;

    @Test
    void identicalVectorsHaveCosineOne() {
        float[] v = {1f, 2f, 3f, 4f};
        assertEquals(1.0, VectorMath.cosine(v, v), EPS);
    }

    @Test
    void oppositeVectorsHaveCosineMinusOne() {
        float[] a = {1f, 2f, 3f};
        float[] b = {-1f, -2f, -3f};
        assertEquals(-1.0, VectorMath.cosine(a, b), EPS);
    }

    @Test
    void orthogonalVectorsHaveCosineZero() {
        assertEquals(0.0, VectorMath.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}), EPS);
    }

    @Test
    void zeroVectorYieldsZeroNotNaN() {
        assertEquals(0.0, VectorMath.cosine(new float[] {0f, 0f}, new float[] {1f, 1f}), EPS);
        assertEquals(0.0, VectorMath.cosine(new float[] {0f, 0f}, new float[] {0f, 0f}), EPS);
    }

    @Test
    void dimensionMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorMath.cosine(new float[] {1f, 2f}, new float[] {1f, 2f, 3f}));
    }

    @Test
    void cosineIsScaleInvariant() {
        float[] a = {3f, 4f, 0f};
        float[] scaled = {30f, 40f, 0f};
        assertEquals(1.0, VectorMath.cosine(a, scaled), EPS);
    }

    @Test
    void cosineStaysInRangeForSeededRandomVectors() {
        Random random = new Random(424242L);
        for (int trial = 0; trial < 500; trial++) {
            int dim = 1 + random.nextInt(256);
            float[] a = randomVector(random, dim);
            float[] b = randomVector(random, dim);
            double c = VectorMath.cosine(a, b);
            assertTrue(c >= -1.0 - EPS && c <= 1.0 + EPS,
                    "cosine must stay in [-1, 1], got " + c + " (trial " + trial + ")");
        }
    }

    private static float[] randomVector(Random random, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = random.nextFloat() * 2f - 1f;
        }
        return v;
    }
}
