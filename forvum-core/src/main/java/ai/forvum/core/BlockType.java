package ai.forvum.core;

/**
 * Structural classification of a {@code messages} row, the discriminator session compaction
 * (P2-COMPACT, ULTRAPLAN section 7.2 item 20) uses to decide what survives a compaction pass.
 *
 * <p>The {@link Role} column says <em>who</em> spoke (user/assistant/system/tool); {@code BlockType}
 * says <em>what kind</em> of block a row is, so the compactor can strip orphaned reasoning/artifact
 * blocks while conservatively retaining tool-execution blocks still connected to a retained turn:
 *
 * <ul>
 *   <li>{@link #TURN_MESSAGE} — a normal conversational message (the v0.1 default; every pre-P2-COMPACT
 *       row is this). Always compaction-eligible by turn position, never stripped as an orphan.</li>
 *   <li>{@link #TURN_REASONING} — a model reasoning/thinking block. Stripped when it is older than the
 *       oldest retained {@code USER} message (an orphan), since reasoning has no value once its turn
 *       has fallen out of the retained window.</li>
 *   <li>{@link #TURN_ARTIFACT} — a generated intermediate artifact attached to a turn. Stripped on the
 *       same orphan rule as reasoning.</li>
 *   <li>{@link #TOOL_EXECUTION} — a tool call/result block. Conservatively <em>retained</em> when it
 *       still belongs to a retained turn (created at or after the oldest retained user message), since
 *       a tool result can carry load-bearing context; stripped only once it is older than that
 *       boundary.</li>
 * </ul>
 *
 * <p>Each constant carries its DB-literal string ({@link #dbValue()} / {@link #fromDbValue(String)}),
 * mirroring the {@code messages.block_type} value the V2 compaction migration adds. Changing the set
 * requires a coordinated update here plus a forward-only Flyway migration.
 */
public enum BlockType {
    TURN_MESSAGE("turn_message"),
    TURN_REASONING("turn_reasoning"),
    TURN_ARTIFACT("turn_artifact"),
    TOOL_EXECUTION("tool_execution");

    private final String dbValue;

    BlockType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static BlockType fromDbValue(String value) {
        for (BlockType b : values()) {
            if (b.dbValue.equals(value)) {
                return b;
            }
        }
        throw new IllegalStateException(
            "Unknown block_type value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}
