package ai.forvum.engine.replay;

/**
 * One recorded element of a replayed session: either a conversational {@link MessageSegment} or a
 * {@link ToolSegment} (a ledgered tool call). Sealed so the {@code forvum replay} command can render each
 * kind with an exhaustive {@code switch}. {@link #createdAt()} is the row's wall-clock timestamp
 * (milliseconds since epoch), used to order tools within their turn.
 *
 * <p>In-process only — a {@code SessionReplayer} builds these from JDBC rows and the command prints them;
 * they are never JSON-serialized nor reflectively instantiated, so (like the {@code doctor} report records)
 * they carry no {@code @RegisterForReflection} and the native image reaches them through direct calls.
 */
public sealed interface ReplaySegment permits MessageSegment, ToolSegment {

    /** Milliseconds since epoch when this row was written. */
    long createdAt();
}
