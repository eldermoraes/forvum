package ai.forvum.engine.session.compaction;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The thresholds that govern session compaction (P2-COMPACT, ULTRAPLAN section 7.2 item 20).
 *
 * <p>Token counts are model-context units estimated from message content (see
 * {@link SessionCompactor#estimateTokens}). A session is compacted when its non-prefix token estimate
 * exceeds {@code reserveFloorTokens}; compaction then drops oldest turns until the retained
 * (most-recent) turns fit within {@code retainTokens}, summarizing the dropped run into one message.
 *
 * @param reserveFloorTokens the floor above which a session must be compacted (the trigger threshold)
 * @param retainTokens       the token budget the most-recent retained turns must fit within after a pass
 */
@RegisterForReflection
public record CompactionPolicy(int reserveFloorTokens, int retainTokens) {

    public CompactionPolicy {
        if (reserveFloorTokens <= 0) {
            throw new IllegalArgumentException(
                "reserveFloorTokens must be positive, got " + reserveFloorTokens);
        }
        if (retainTokens <= 0 || retainTokens > reserveFloorTokens) {
            throw new IllegalArgumentException(
                "retainTokens must be positive and <= reserveFloorTokens (" + reserveFloorTokens
              + "), got " + retainTokens);
        }
    }
}
