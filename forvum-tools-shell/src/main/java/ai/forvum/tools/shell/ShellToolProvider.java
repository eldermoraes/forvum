package ai.forvum.tools.shell;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shell tool extension (PR-6 / P2-2 #27, ULTRAPLAN §9.2.5). Contributes the {@code shell.exec} tool to
 * the engine's global ToolRegistry, which discovers this {@code @ApplicationScoped} bean via CDI and
 * (M18 Option A) executes it through {@link #invoke(String, Map)}. The engine's {@code ToolExecutor} is the
 * single belt + RBAC + #39 approval gate and audits every call; this provider only, on a permitted +
 * approved call, validates the argv against {@code tools/shell.json} and launches the process — it never
 * gates or audits (no reflection, no AI library).
 *
 * <p>The defense layers, in order, are: (1) the engine belt + (2) the {@link ai.forvum.core.PermissionScope#SHELL_EXEC}
 * RBAC scope + (3) the #39 user-confirm gate (all in the engine), then (4) the {@link ShellAllowlist}
 * allowlist + (5) the {@link WorkspaceRoot} working-directory confinement + (6) {@link ShellExecutor}'s
 * LIST-form, scrubbed-env, timeout-bounded launch (this module).
 */
@ForvumExtension
@ApplicationScoped
public class ShellToolProvider extends AbstractToolProvider {

    @Inject
    ShellAllowlist allowlist;

    @Inject
    WorkspaceRoot workspace;

    private final ShellExecutor executor = new ShellExecutor();

    @Override
    public String extensionId() {
        return "shell";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(ShellExecTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        if (!"shell.exec".equals(toolName)) {
            throw new IllegalArgumentException(
                    "ShellToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides shell.exec.");
        }
        List<String> argv = argvArg(arguments);
        ShellAllowlist.Spec spec = allowlist.read();
        spec.validate(argv);

        String requestedWorkingDir = stringOrNull(arguments.get("workingDir"));
        String effectiveWorkingDir = requestedWorkingDir != null
                ? requestedWorkingDir
                : spec.workingDir().orElse(null);
        Path workingDir = workspace.confine(effectiveWorkingDir);

        return executor.run(argv, workingDir, spec.timeoutSeconds());
    }

    /** Extract the required {@code argv} array argument as a {@code List<String>}. */
    private static List<String> argvArg(Map<String, Object> arguments) {
        Object value = arguments.get("argv");
        if (value == null) {
            throw new IllegalArgumentException("shell.exec requires an 'argv' array argument.");
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "shell.exec 'argv' must be a JSON array of strings, got: " + value.getClass().getSimpleName());
        }
        List<String> argv = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                throw new IllegalArgumentException("shell.exec 'argv' must not contain null elements.");
            }
            argv.add(element.toString());
        }
        return argv;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
