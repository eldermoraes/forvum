package ai.forvum.engine.session.compaction;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The outcome of one {@link SessionCompactor#compact} pass (P2-COMPACT). When {@code compacted} is
 * false the session was below the reserve floor and nothing changed; otherwise the counters report what
 * the pass did, for the ledger/CAPR dashboard and for tests to pin the invariants.
 *
 * @param compacted         whether the session was over the floor and a pass ran
 * @param summarizedTurns   how many oldest messages were folded into the single summary message
 * @param orphansStripped   how many orphaned reasoning/artifact/stale-tool blocks were deleted
 * @param caprArchived      how many {@code capr_events} rows were marked {@code is_archived}
 * @param cachedPrefixEndIndex the prefix boundary recorded on the session (null when none was set)
 */
@RegisterForReflection
public record CompactionResult(
        boolean compacted,
        int summarizedTurns,
        int orphansStripped,
        int caprArchived,
        Integer cachedPrefixEndIndex) {

    /** A no-op result for a session that was under the reserve floor. */
    static CompactionResult notCompacted(Integer cachedPrefixEndIndex) {
        return new CompactionResult(false, 0, 0, 0, cachedPrefixEndIndex);
    }
}
