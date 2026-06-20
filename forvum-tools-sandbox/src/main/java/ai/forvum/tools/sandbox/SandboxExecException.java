package ai.forvum.tools.sandbox;

/**
 * Thrown when a {@code sandbox.run} container exits with a non-zero status, is killed because it exceeded
 * the configured timeout, or could not be launched (no container runtime present, fail-closed config)
 * (ULTRAPLAN §9.2.5, #27). The engine's {@code ToolExecutor} catches it, records the invocation
 * {@code error}, and rethrows the message to the model. The captured (size-bounded) container output is
 * carried in the message so the model sees what the code produced before it failed.
 */
public final class SandboxExecException extends RuntimeException {

    public SandboxExecException(String message) {
        super(message);
    }
}
