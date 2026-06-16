package ai.forvum.engine.security;

import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.sdk.OutputContext;
import ai.forvum.sdk.OutputGuard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes every configured {@link OutputGuard} over a candidate egress at the pre-channel-emit seam
 * (P2-OUTPUTGUARD, DR-6a §9.2.3). The fold is <strong>fail-closed and most-restrictive-wins</strong>:
 * any {@code Blocked} dominates a {@code Redacted} dominates {@code Allowed}, and a guard that throws or
 * returns {@code null} is treated as {@code Blocked} rather than allowed to leak. Redactions chain — each
 * guard sees the prior guard's (possibly already-redacted) output — and their counts union.
 *
 * <p>The composition lives here, not in the SPI, so a plugin guard stays single-responsibility (DR-6a).
 * It runs blocking on the turn's virtual thread (no reactive types; §3.8) and is IO-free.
 */
@ApplicationScoped
public class OutputGuardChain {

    @Inject
    Instance<OutputGuard> guards;

    /**
     * Run the configured guards over {@code candidate}; return the egress text to emit. A {@code Blocked}
     * disposition (a guard suppressed the egress) throws {@link OutputFilteredException}, which the turn
     * boundary ({@code TurnService}) renders as a terminal {@code output_filtered} error rather than leak.
     */
    public String enforce(OutputContext ctx, String candidate) {
        return enforce(resolve(), ctx, candidate);
    }

    /** Pure overload (package-private for unit tests): compose the guards and map the disposition. */
    static String enforce(List<OutputGuard> guards, OutputContext ctx, String candidate) {
        FilteringOutcome outcome = compose(guards, ctx, candidate);
        return switch (outcome) {
            case FilteringOutcome.Allowed a -> a.content();
            case FilteringOutcome.Redacted r -> r.content();
            case FilteringOutcome.Blocked b -> throw new OutputFilteredException(b.reason(), ctx.turnId());
        };
    }

    private List<OutputGuard> resolve() {
        List<OutputGuard> resolved = new ArrayList<>();
        for (OutputGuard guard : guards) {
            resolved.add(guard);
        }
        return resolved;
    }

    /**
     * Pure, CDI-free fold (package-private for unit tests): {@code Blocked} short-circuits as the most
     * restrictive disposition; {@code Redacted} chains its content forward and accumulates the count; a
     * thrown or {@code null} outcome is folded to {@code Blocked} (fail-closed). With no guards the
     * candidate passes through unchanged.
     */
    static FilteringOutcome compose(List<OutputGuard> guards, OutputContext ctx, String candidate) {
        String current = candidate;
        int totalRedactions = 0;
        for (OutputGuard guard : guards) {
            FilteringOutcome outcome;
            try {
                outcome = guard.filter(ctx, current);
            } catch (RuntimeException e) {
                return new FilteringOutcome.Blocked(
                    "output guard " + guard.getClass().getSimpleName() + " failed: " + e.getMessage());
            }
            if (outcome == null) {
                return new FilteringOutcome.Blocked(
                    "output guard " + guard.getClass().getSimpleName() + " returned no outcome");
            }
            switch (outcome) {
                case FilteringOutcome.Blocked b -> {
                    return b;
                }
                case FilteringOutcome.Redacted r -> {
                    current = r.content();
                    totalRedactions += r.redactions();
                }
                case FilteringOutcome.Allowed a -> current = a.content();
            }
        }
        return totalRedactions > 0
            ? new FilteringOutcome.Redacted(current, totalRedactions)
            : new FilteringOutcome.Allowed(current);
    }
}
