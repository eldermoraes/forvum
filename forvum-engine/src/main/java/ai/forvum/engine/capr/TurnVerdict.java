package ai.forvum.engine.capr;

/**
 * The per-turn judge verdict (#195): a pass/fail decision plus a normalized {@code [0,1]} score and a
 * short human reason (on failure, prefixed with a {@code FailureClass}-compatible token by the caller).
 * Engine-local and never JSON-serialized, so it carries no {@code @RegisterForReflection}.
 *
 * <p>The score contract rejects NaN explicitly: {@code Double.isNaN(x)} makes both {@code x < 0} and
 * {@code x > 1} false, so NaN would slip a naive range check (repo lesson [P2-5]).
 *
 * @param passed whether the reply satisfied the turn's request
 * @param score  the normalized verdict score in {@code [0, 1]} (never NaN)
 * @param reason the short human reason for the verdict (non-blank)
 */
public record TurnVerdict(boolean passed, double score, String reason) {

    public TurnVerdict {
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalStateException(
                    "TurnVerdict score must be in [0, 1] and not NaN. Got " + score + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("TurnVerdict reason must be non-blank.");
        }
    }
}
