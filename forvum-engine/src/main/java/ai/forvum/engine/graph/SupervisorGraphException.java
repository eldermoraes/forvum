package ai.forvum.engine.graph;

/**
 * Thrown when the supervisor {@code StateGraph} fails to compile or run a turn (ULTRAPLAN section 5.5).
 * Unchecked because the only legitimate catcher is the engine turn layer ({@code Agent.respond}), which
 * surfaces it as a terminal error event — node logic does not recover mid-graph.
 */
public class SupervisorGraphException extends RuntimeException {

    public SupervisorGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
