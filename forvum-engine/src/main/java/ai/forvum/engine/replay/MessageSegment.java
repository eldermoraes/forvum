package ai.forvum.engine.replay;

/**
 * A replayed conversational message: its {@code role} (the raw {@code messages.role} literal — {@code user},
 * {@code assistant}, {@code system}, or {@code tool}) and {@code content}, with the row's {@code created_at}.
 */
public record MessageSegment(String role, String content, long createdAt) implements ReplaySegment {
}
