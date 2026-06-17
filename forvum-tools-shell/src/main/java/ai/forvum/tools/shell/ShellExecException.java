package ai.forvum.tools.shell;

/**
 * Thrown when a {@code shell.exec} process exits with a non-zero status or is killed because it exceeded
 * the allowlist timeout (ULTRAPLAN §9.2.5). The engine's {@code ToolExecutor} catches it, records the
 * invocation {@code error}, and rethrows the message to the model. The captured (size-bounded) output is
 * carried in the message so the model sees what the command produced before it failed.
 */
public final class ShellExecException extends RuntimeException {

    public ShellExecException(String message) {
        super(message);
    }
}
