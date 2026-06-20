package ai.forvum.tools.sandbox;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

/**
 * The {@code sandbox.run} tool spec (ULTRAPLAN §9.2.5, PR-6 / #27): run a code snippet (or an explicit
 * in-container argv) inside an EPHEMERAL container — a SAFE sibling of {@code shell.exec} that contains the
 * execution rather than running it directly on the host. It shares {@code shell.exec}'s
 * {@link PermissionScope#SHELL_EXEC} (the enum doc names it "and its sandboxed sibling") and is
 * {@link ToolSpec#userConfirmRequired()} {@code = true} (the 5-argument constructor), opting every
 * invocation into the P2-14 #39 blocking user-approval gate — the engine's {@code ToolExecutor} parks the
 * call through the SQLite-backed approval queue AFTER the belt and RBAC gates pass. The sandbox module adds
 * NO approval code; the flag is the entire opt-in.
 *
 * <p>The parameters schema declares an optional {@code code} string (run through the configured
 * interpreter), an optional {@code argv} ARRAY of strings (an explicit in-container command — exactly one of
 * {@code code}/{@code argv} per call), an optional {@code workingDir} string (workspace-relative, bind-
 * mounted into the container), and an optional {@code writable} boolean (a read-write workspace mount;
 * default read-only). The image, runtime, resource limits, and network policy are operator config in
 * {@code tools/sandbox.json}, never model-controlled.
 */
public final class SandboxRunTool {

    /** The tool this module contributes; built by {@code SandboxToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "sandbox.run",
            "Run a code snippet (or an explicit command vector) inside an ephemeral, locked-down container "
          + "(no network by default, a read-only root filesystem, dropped Linux capabilities, a non-root "
          + "user, and CPU/memory/time limits), then return its captured output. Provide EITHER 'code' (run "
          + "through the operator-configured interpreter) OR 'argv' (an explicit in-container command), not "
          + "both. The container image and limits are operator config, not selectable here.",
            PermissionScope.SHELL_EXEC,
            "{\"type\":\"object\",\"properties\":{"
          + "\"code\":{\"type\":\"string\","
          + "\"description\":\"a code snippet to run through the configured interpreter\"},"
          + "\"argv\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
          + "\"description\":\"an explicit in-container command vector (program + arguments)\"},"
          + "\"workingDir\":{\"type\":\"string\","
          + "\"description\":\"optional workspace-relative directory bind-mounted into the container\"},"
          + "\"writable\":{\"type\":\"boolean\","
          + "\"description\":\"mount the workspace read-write (default false: read-only)\"}}}",
            true);

    private SandboxRunTool() {
    }
}
