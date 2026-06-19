package ai.forvum.engine.memoryquery;

/**
 * Pure vector math for the linear nearest-neighbor scan (P3-2, #50, Risk #2). Cosine similarity over
 * {@code float[]} vectors decoded from the {@code semantic_memory.embedding} BLOBs.
 *
 * <p><strong>Risk #2 decision — linear, not {@code vec0}.</strong> {@code sqlite-vec} (the {@code vec0}
 * virtual table) is a C extension with no published Maven artifact and would require loading a second
 * runtime native library into SQLite ({@code load_extension}) — a new native surface forbidden by the
 * native-first mandate (CLAUDE.md §5: SQLite JNI is the ONLY allowed native pin). The issue explicitly
 * permits a linear fallback ("defer vec0 if linear is acceptable at 100k"). A pure-Java cosine scan adds
 * ZERO native surface and is fast enough: benchmarked at ~9 ms/query (10k rows) and ~93 ms/query (100k
 * rows) for 768-dim vectors, top-K 10 — well within CLI latency. So Forvum ships the linear scan.
 *
 * <p>Stateless and allocation-free per pair, so a plain unit test covers it with no CDI or DB.
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * Cosine similarity of two equal-length vectors, in {@code [-1, 1]}. Returns {@code 0} when either
     * vector is the zero vector (undefined cosine), so a degenerate embedding never poisons the ranking.
     *
     * @throws IllegalArgumentException if the vectors differ in length (mismatched embedding dimensions —
     *     e.g. a query embedded by a different model than the stored rows)
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: query has " + a.length + " dims, stored vector has " + b.length
                            + " (was the query embedded by a different model than the indexed rows?)");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
