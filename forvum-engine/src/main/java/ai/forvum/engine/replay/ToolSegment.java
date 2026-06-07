package ai.forvum.engine.replay;

/**
 * A replayed tool call as recorded in {@code tool_invocations}: the {@code toolName}, the JSON
 * {@code arguments}, the {@code result} (JSON or truncated text, possibly null), the outcome
 * {@code status} ({@code ok}/{@code error}/{@code denied}), an optional {@code latencyMs}, and the row's
 * {@code created_at}.
 */
public record ToolSegment(
        String toolName,
        String arguments,
        String result,
        String status,
        Integer latencyMs,
        long createdAt) implements ReplaySegment {
}
