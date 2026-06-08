package ai.forvum.engine.graph;

/**
 * Thrown when a turn declares a per-agent {@code outputSchema} (P2-12) and the final assistant reply does
 * not satisfy it — the reply is not valid JSON, or it violates the declared shape (wrong root type, a
 * missing required field, or a property of the wrong primitive type). The supervisor wraps this in a
 * {@link SupervisorGraphException} so {@code TurnService} surfaces it as a terminal {@code ErrorEvent}
 * naming the schema and the validation failure; there is no retry.
 */
public class OutputSchemaException extends RuntimeException {

    public OutputSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
