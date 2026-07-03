package ai.forvum.engine.compress;

/**
 * The result of a bounded-compression pass (#176): the text that may re-enter the context window — never
 * larger than the budget's {@code maxOutputChars} — and the {@link CompressionOutcome} that produced it.
 *
 * @param text    the compressed or bounded-fallback text (never null).
 * @param outcome how the text was produced (never null).
 */
public record CompressionResult(String text, CompressionOutcome outcome) {
    public CompressionResult {
        if (text == null) {
            throw new IllegalArgumentException("CompressionResult text must be non-null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("CompressionResult outcome must be non-null");
        }
    }
}
