package ai.forvum.core.event;

/** Stable reason tokens for {@link FallbackTriggered#reason} (ULTRAPLAN section 4.3.2). */
public final class FallbackReasons {
    public static final String RATE_LIMIT   = "rate_limit";
    public static final String TIMEOUT      = "timeout";
    public static final String SERVER_ERROR = "server_error";
    public static final String COST_BUDGET  = "cost_budget";

    private FallbackReasons() {}
}
