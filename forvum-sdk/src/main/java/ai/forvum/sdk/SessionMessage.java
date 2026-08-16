package ai.forvum.sdk;

/**
 * One transcript message as surfaced by the {@link SessionAccess} seam (#189) — a projection of the
 * engine's {@code messages} row for the model-facing {@code sessions.history} tool. JDK types only, so
 * the tool module carries no persistence dependency and this record stays reflection-free and
 * native-safe (it is rendered to text for the model, never JSON-serialized).
 *
 * @param role      the message role ({@code user}, {@code assistant}, ...)
 * @param content   the message text
 * @param createdAt epoch millis the message was recorded
 */
public record SessionMessage(String role, String content, long createdAt) {

    public SessionMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("SessionMessage role must be non-null and non-blank.");
        }
        if (content == null) {
            throw new IllegalArgumentException("SessionMessage content must be non-null.");
        }
    }
}
