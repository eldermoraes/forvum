package ai.forvum.engine.memoryquery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes/decodes an embedding vector ({@code float[]}) to/from the {@code semantic_memory.embedding}
 * BLOB (P3-2, #50). The BLOB is a flat little-endian {@code float32} array — self-describing: its
 * dimension is {@code bytes.length / 4}, so no separate dimension column is needed (the existing V1/V5
 * {@code embedding BLOB} column already holds it). Pure and stateless: a plain unit/property test covers
 * the round-trip with zero CDI or DB.
 *
 * <p>Little-endian is fixed (independent of the host's {@link ByteOrder#nativeOrder()}), so a BLOB written
 * on one machine decodes identically on another — the store is portable.
 */
public final class VectorCodec {

    private static final int BYTES_PER_FLOAT = Float.BYTES; // 4

    private VectorCodec() {
    }

    /** Encode a vector to a little-endian float32 BLOB. {@code null} encodes to {@code null}. */
    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * Decode a little-endian float32 BLOB back to a vector. {@code null} or a zero-length BLOB decodes to
     * {@code null} (no usable vector). A BLOB whose length is not a multiple of 4 is malformed and is
     * rejected with {@link IllegalArgumentException}, never silently truncated.
     */
    public static float[] decode(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (blob.length % BYTES_PER_FLOAT != 0) {
            throw new IllegalArgumentException(
                    "Embedding BLOB length " + blob.length + " is not a multiple of " + BYTES_PER_FLOAT
                            + " (corrupt float32 vector)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[blob.length / BYTES_PER_FLOAT];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
