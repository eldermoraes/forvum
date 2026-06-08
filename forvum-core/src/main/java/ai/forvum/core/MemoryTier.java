package ai.forvum.core;

/**
 * The memory tier a retrieved {@code MemoryHit} comes from (ULTRAPLAN section 4.2 schema; the
 * three-tier Write surface of the Context-Engineering paradigm). A {@link MemoryPolicy} selects which
 * tiers a {@code MemoryProvider} is allowed to draw from.
 *
 * <ul>
 *   <li>{@link #MESSAGES} — append-only chat history ({@code messages} table).</li>
 *   <li>{@link #EPISODIC} — per-agent, per-session event log ({@code episodic_memory} table).</li>
 *   <li>{@link #SEMANTIC} — embedded long-term facts ({@code semantic_memory} table).</li>
 * </ul>
 */
public enum MemoryTier {
    MESSAGES,
    EPISODIC,
    SEMANTIC
}
