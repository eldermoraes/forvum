package ai.forvum.core.budget;

/**
 * Atomic snapshot of a {@link CostBudget}'s current usage, produced
 * by a single {@code SUM()} trip over {@code provider_calls} and
 * consumed jointly by the enforcement path and observability layers.
 *
 * <p><b>Atomicity.</b> All four fields derive from the same SQL
 * aggregation; no caller should recompute any field from the others.
 * The snapshot is a point-in-time read — subsequent calls can return
 * different values as concurrent turns advance the ledger.
 *
 * <p><b>Biconditional invariant.</b> The canonical constructor
 * enforces {@code cause != null} if and only if
 * {@code exhausted == true}. A violation throws
 * {@link IllegalStateException} and names which side of the
 * biconditional was broken (see constructor source).
 */
public record Usage(
    Spend spent,              // already consumed (usd + tokens)
    Spend remaining,          // headroom; individual dimensions may be
                              // null when the matching cap on CostBudget
                              // is null (opt-out per Decision 1)
    boolean exhausted,        // any non-null cap reached
    ExhaustionCause cause     // null iff exhausted == false
) {
    public Usage {
        if (spent == null) {
            throw new IllegalStateException(
                "Usage spent must be non-null. Construct via BudgetMeter "
              + "or test fixtures — a null here indicates a programmatic "
              + "construction bug.");
        }
        if (remaining == null) {
            throw new IllegalStateException(
                "Usage remaining must be non-null. Individual dimensions "
              + "inside the Spend may be null (opt-out), but the Spend "
              + "record itself must be present.");
        }
        if (exhausted && cause == null) {
            throw new IllegalStateException(
                "Usage cause must accompany exhausted=true. Either the "
              + "ExhaustionCause was lost between BudgetMeter query and "
              + "Usage construction, or the exhausted flag was set "
              + "without computing cause. Check BudgetMeter output.");
        }
        if (!exhausted && cause != null) {
            throw new IllegalStateException(
                "Usage cause must be null when exhausted=false. A non-null "
              + "cause paired with exhausted=false indicates a caller "
              + "populated cause without flipping the exhausted flag.");
        }
    }
}
