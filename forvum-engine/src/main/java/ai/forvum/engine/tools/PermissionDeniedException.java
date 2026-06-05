package ai.forvum.engine.tools;

/**
 * Thrown by the {@code ToolExecutor} when an agent invokes a tool whose required {@code PermissionScope}
 * is not reachable from the agent's allowed-tools set — i.e. the tool is not in the agent's filtered
 * belt (ULTRAPLAN section 5.3). The denied attempt is audited to {@code tool_invocations} with
 * {@code status = 'denied'} before this is thrown. Engine-local: the executor enforces in-engine, so no
 * Layer-3 tool plugin ever throws it.
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
