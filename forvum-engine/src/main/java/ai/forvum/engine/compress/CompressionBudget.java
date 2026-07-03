package ai.forvum.engine.compress;

/**
 * The character budgets for one compression boundary (#176). Chars, not tokens — native-clean, no
 * tokenizer (CLAUDE.md section 5; DR-5).
 *
 * <ul>
 *   <li>{@code thresholdChars} — the size above which a chunk is compressed ({@code <= 0} disables
 *       compression, matching the pre-#56 pass-through).</li>
 *   <li>{@code maxOutputChars} — the HARD ceiling on what may re-enter the context window, so a
 *       summarizer failure can never exceed it.</li>
 *   <li>{@code maxInputChars} — the size above which the model is skipped entirely (bounded memory use,
 *       no expensive call on adversarially-huge content).</li>
 * </ul>
 *
 * <p>Each call-site builds its own budget, so retrieved-memory and worker-digest boundaries are
 * independently tunable even though both derive from the same {@code compressThresholdChars} knob today.
 *
 * @param thresholdChars compress above this (0 disables); may be 0 but not negative in derived budgets.
 * @param maxInputChars  skip the model above this; must be {@code >= thresholdChars}.
 * @param maxOutputChars the hard ceiling on the result; must be {@code > 0}.
 */
public record CompressionBudget(int thresholdChars, int maxInputChars, int maxOutputChars) {

    /** How much larger than the threshold a chunk may be before the model is skipped altogether. */
    private static final int INPUT_FACTOR = 4;

    public CompressionBudget {
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars must be positive, got " + maxOutputChars);
        }
        if (maxInputChars < thresholdChars) {
            throw new IllegalArgumentException(
                "maxInputChars (" + maxInputChars + ") must be >= thresholdChars (" + thresholdChars + ")");
        }
    }

    /**
     * Derive a budget from the single {@code compressThresholdChars} knob (DR-5): the fallback ceiling is
     * the threshold itself (a failed compression must not leave content above the size that triggered it),
     * and the model is skipped above {@code threshold * 4} (clamped against int overflow). A non-positive
     * threshold still yields a positive {@code maxOutputChars} so the fallback truncation stays well-defined.
     */
    public static CompressionBudget fromThreshold(int thresholdChars) {
        int maxOutput = Math.max(thresholdChars, 1);
        int maxInput = (int) Math.min((long) Math.max(thresholdChars, 0) * INPUT_FACTOR, Integer.MAX_VALUE);
        return new CompressionBudget(thresholdChars, Math.max(maxInput, maxOutput), maxOutput);
    }
}
