package ai.forvum.engine.tools;

/**
 * Thrown by the {@code ToolExecutor} when a {@code USER_CONFIRM_REQUIRED} tool call that cleared the belt
 * and RBAC scope gates is NOT approved — the owner rejected it, it timed out, or it ran in a
 * non-interactive context with no approval surface (P2-14 #39). The denied attempt is audited to
 * {@code tool_invocations} with {@code status = 'denied'} before this is thrown, exactly like a belt/scope
 * miss.
 *
 * <p>Extends {@link PermissionDeniedException} so any code that treats a permission denial uniformly still
 * does (a declined approval IS a denial), while the supervisor graph's tool loop catches this subtype
 * first to render a clearer "declined by the user" result back to the model — the turn still completes
 * rather than aborting.
 */
public class ApprovalDeniedException extends PermissionDeniedException {

    public ApprovalDeniedException(String message) {
        super(message);
    }
}
