package ai.forvum.engine.graph;

/**
 * The per-replay binding seam (#57). When {@link #CURRENT_REPLAY} is bound on the turn's thread, the
 * {@link SupervisorGraph} runs in replay mode: it serves recorded tool outputs from the bound
 * {@link ReplayToolSource} instead of executing (or auditing) real tools, skips memory retrieval, and
 * disables proxy-model compression — so a substituted-model rerun is deterministic, the recorded results
 * short-circuiting every non-deterministic re-execution and compression step.
 *
 * <p>Mirrors the {@code CurrentAgent.CURRENT_AGENT} {@code ScopedValue} seam: it is bound at the replay
 * turn entry ({@code SessionSubstitutionReplayer}) and read in the graph on the same virtual thread, the
 * binding proven to survive the {@code respond → SupervisorGraph → runTool} chain.
 */
public final class ReplayContext {

    /** Bound during a session replay-with-substitution rerun; unbound for every normal turn. */
    public static final ScopedValue<ReplayToolSource> CURRENT_REPLAY = ScopedValue.newInstance();

    private ReplayContext() {
    }
}
