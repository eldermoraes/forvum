package ai.forvum.tools.shell;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

/**
 * The {@code shell.exec} tool spec (ULTRAPLAN §9.2.5, PR-6 / #27): run a process from a validated
 * {@code argv} vector, bounded by the {@code tools/shell.json} allowlist. It is the FIRST tool to declare
 * {@link ToolSpec#userConfirmRequired()} {@code = true} (the 5-argument constructor), opting every
 * invocation into the P2-14 #39 blocking user-approval gate — the engine's {@code ToolExecutor} parks the
 * call through the SQLite-backed approval queue AFTER the belt and RBAC ({@link PermissionScope#SHELL_EXEC})
 * gates pass. The shell module adds NO approval code; the flag is the entire opt-in.
 *
 * <p>The parameters schema declares {@code argv} as an ARRAY of strings (the LIST-form exec vector — never
 * a {@code sh -c} command line) and an optional {@code workingDir} string (workspace-relative). The array
 * shape is what the PR-6 {@code ToolCallBridge} array-schema support offers to the model.
 */
public final class ShellExecTool {

    /** The tool this module contributes; built by {@code ShellToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "shell.exec",
            "Run a process from an argv vector (program + arguments), bounded by the operator's "
          + "tools/shell.json allowlist, and return its captured output. The first element is the command "
          + "(a bare name resolved against PATH, or an absolute path); the rest are arguments. This is NOT "
          + "a shell command line — no quoting, globbing, pipes, or redirection.",
            PermissionScope.SHELL_EXEC,
            "{\"type\":\"object\",\"properties\":{"
          + "\"argv\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
          + "\"description\":\"the command and its arguments, one element each\"},"
          + "\"workingDir\":{\"type\":\"string\","
          + "\"description\":\"optional workspace-relative working directory\"}},"
          + "\"required\":[\"argv\"]}",
            true);

    private ShellExecTool() {
    }
}
