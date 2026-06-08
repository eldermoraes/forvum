package ai.forvum.provider.memory.qdrant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A DETERMINISTIC, dependency-free REFERENCE embedding: a fixed-dimension hashing bag-of-words vector,
 * L2-normalized. It exists ONLY so this reference module is hermetic and native-clean — its tests need no
 * external embedding model and no heavy ML dependency on the build classpath.
 *
 * <p><strong>Reference-only.</strong> This is NOT a semantically meaningful embedding: it captures token
 * co-occurrence, not meaning, so vector search against it is a structural demonstration, not production
 * retrieval quality. An operator running Qdrant for real supplies a proper embedding model at ingestion
 * AND query time (e.g. via the provider-onboarding path) and points Forvum's METADATA strategy or a real
 * embedding service at the query side; the deterministic vector here keeps the reference self-contained.
 *
 * <p>Determinism: the same text always yields the same vector (a fixed dimension, a fixed token hash, a
 * fixed normalization), so request-building is reproducible and unit-testable.
 */
public final class ReferenceEmbedding {

    /** Vector dimension of the reference embedding. Operators must create the collection with this size. */
    public static final int DIMENSION = 64;

    private ReferenceEmbedding() {
    }

    /**
     * Embed {@code text} into a {@link #DIMENSION}-length, L2-normalized vector. Lower-cases, splits on
     * non-alphanumeric runs, hashes each token into a bucket, and accumulates; a blank/empty text yields
     * the zero vector.
     */
    public static List<Float> embed(String text) {
        float[] acc = new float[DIMENSION];
        if (text != null && !text.isBlank()) {
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int bucket = Math.floorMod(token.hashCode(), DIMENSION);
                acc[bucket] += 1.0f;
            }
        }
        double norm = 0.0;
        for (float v : acc) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        List<Float> vector = new ArrayList<>(DIMENSION);
        if (norm == 0.0) {
            for (int i = 0; i < DIMENSION; i++) {
                vector.add(0.0f);
            }
        } else {
            for (float v : acc) {
                vector.add((float) (v / norm));
            }
        }
        return List.copyOf(vector);
    }
}
