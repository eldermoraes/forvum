package ai.forvum.core.security;

/**
 * The disposition of running an {@code OutputGuard} over a candidate egress (ULTRAPLAN section 9.2.1,
 * DR-6a). A {@code forvum-core} (Layer 0) type because the {@code OutputGuard} SPI in {@code forvum-sdk}
 * returns it and the SDK may depend only on {@code forvum-core}.
 *
 * <p>Sealed with exactly three outcome subtypes — the orthogonal pass / redact / suppress axis. The
 * {@code FILTERED} label from the task brief is realized as the {@code FallbackReasons.FILTERED} reason
 * token on the {@link Blocked} (hard-trip) path, NOT as a fourth subtype (DR-6a DP-5).
 *
 * <p>Reflection for native is registered from the {@code forvum-engine}
 * {@code CoreReflectionRegistration} holder (ULTRAPLAN section 6.3) — these records carry no
 * {@code @RegisterForReflection} themselves ({@code forvum-core} bans {@code io.quarkus*}).
 */
public sealed interface FilteringOutcome
        permits FilteringOutcome.Allowed,
                FilteringOutcome.Redacted,
                FilteringOutcome.Blocked {

    /** Egress passes through unchanged — no sensitive data matched. Carries the original content. */
    record Allowed(String content) implements FilteringOutcome {}

    /**
     * Egress is emitted with matched spans replaced (e.g. {@code "sk-***"}); the user still gets a
     * response, minus the secret/PII. Carries the redacted text and a redaction count for telemetry.
     */
    record Redacted(String content, int redactions) implements FilteringOutcome {
        public Redacted {
            if (redactions < 0) {
                throw new IllegalStateException("redactions must be >= 0, got " + redactions);
            }
        }
    }

    /**
     * Egress is suppressed entirely; the turn surfaces a terminal error / fallback path with
     * {@code reason = FallbackReasons.FILTERED} instead of leaking. Carries the trip reason.
     */
    record Blocked(String reason) implements FilteringOutcome {}
}
