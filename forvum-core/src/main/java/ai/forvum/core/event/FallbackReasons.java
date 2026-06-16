package ai.forvum.core.event;

/** Stable reason tokens for {@link FallbackTriggered#reason} (ULTRAPLAN section 4.3.2). */
public final class FallbackReasons {
    public static final String RATE_LIMIT   = "rate_limit";
    public static final String TIMEOUT      = "timeout";
    public static final String SERVER_ERROR = "server_error";
    public static final String COST_BUDGET  = "cost_budget";

    /**
     * The user-facing telemetry token written when an {@code OutputGuard} {@code Blocked} outcome ends a
     * turn rather than leak a secret/PII (ULTRAPLAN section 9.2.2, DR-6a). It mirrors how
     * {@link #COST_BUDGET} short-circuits; in retry terms a filtered egress is {@code NonRetryable}
     * (retrying produces the same secret) — DR-4c folded it into {@code NonRetryable}, no fourth permit.
     */
    public static final String FILTERED     = "filtered";

    private FallbackReasons() {}
}
