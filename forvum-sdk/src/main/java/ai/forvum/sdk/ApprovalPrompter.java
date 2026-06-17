package ai.forvum.sdk;

/**
 * An interactive surface's hook for resolving a {@code USER_CONFIRM_REQUIRED} tool call synchronously
 * (P2-14 #39, ULTRAPLAN section 7.2 item 14 / section 9.1.b DP-9). A channel or command that can talk to a
 * human in real time — the TUI REPL prompting on the console — binds an implementation on
 * {@link ApprovalContext#PROMPTER} for the duration of a turn; the engine's approval machinery, when it
 * sees the binding, calls {@link #promptApproval} on the turn's own thread to get the owner's decision
 * instead of parking the call for the web dashboard.
 *
 * <p>A {@code @FunctionalInterface} carrying only JDK types keeps {@code forvum-sdk} Quarkus-free and lets
 * a Layer-3 channel implement it depending only on the SDK (CLAUDE.md section 3 / 12).
 */
@FunctionalInterface
public interface ApprovalPrompter {

    /**
     * Ask the owner to approve or reject a tool call. Blocks until the human answers.
     *
     * @return {@code true} to approve the call, {@code false} to reject it
     */
    boolean promptApproval(String agentId, String toolName, String arguments);
}
