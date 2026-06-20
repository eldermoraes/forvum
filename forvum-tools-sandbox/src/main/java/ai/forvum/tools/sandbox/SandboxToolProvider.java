package ai.forvum.tools.sandbox;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sandbox tool extension (PR-6 / P2-2 #27, ULTRAPLAN §9.2.5). Contributes the {@code sandbox.run} tool
 * to the engine's global ToolRegistry, which discovers this {@code @ApplicationScoped} bean via CDI and
 * (M18 Option A) executes it through {@link #invoke(String, Map)}. The engine's {@code ToolExecutor} is the
 * single belt + RBAC + #39 approval gate and audits every call; this provider only, on a permitted +
 * approved call, reads the config, confines the working directory, detects the runtime, builds the hardened
 * container invocation, and launches it — it never gates or audits (no reflection, no AI library).
 *
 * <p>The defense layers, in order, are: (1) the engine belt + (2) the {@link ai.forvum.core.PermissionScope#SHELL_EXEC}
 * RBAC scope + (3) the #39 user-confirm gate (all in the engine), then (4) the {@link SandboxConfig}
 * default-deny config (no image = no run) + (5) the {@link WorkspaceRoot} working-directory confinement +
 * (6) the {@link SandboxCommand} container hardening (no network, read-only root, dropped caps, non-root
 * user, resource limits) + (7) {@link SandboxExecutor}'s LIST-form, scrubbed-env, timeout-bounded launch
 * (this module).
 */
@ForvumExtension
@ApplicationScoped
public class SandboxToolProvider extends AbstractToolProvider {

    @Inject
    SandboxConfig config;

    @Inject
    WorkspaceRoot workspace;

    private final SandboxExecutor executor = new SandboxExecutor();

    @Override
    public String extensionId() {
        return "sandbox";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(SandboxRunTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        if (!"sandbox.run".equals(toolName)) {
            throw new IllegalArgumentException(
                    "SandboxToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides sandbox.run.");
        }
        SandboxConfig.Spec spec = config.read();
        spec.requireRunnable();

        // Validate the local, model-supplied inputs first (cheap, deterministic, host-independent), then
        // resolve the runtime just before launch — so a workspace escape or a bad argv shape fails clearly
        // regardless of whether a container runtime is installed.
        Path hostWorkdir = workspace.confine(stringOrNull(arguments.get("workingDir")));
        String code = stringOrNull(arguments.get("code"));
        List<String> argv = argvArg(arguments.get("argv"));
        boolean writable = Boolean.TRUE.equals(arguments.get("writable"));

        String runtime = ContainerRuntime.resolve(spec.runtime(), System.getenv("PATH"))
                .orElseThrow(() -> new SandboxExecException(
                        "sandbox.run found no container runtime: neither 'podman' nor 'docker' is on PATH. "
                      + "Install one, or pin it via the \"runtime\" key in tools/sandbox.json."));

        List<String> command = SandboxCommand.build(runtime, spec, hostWorkdir, code, argv, writable);
        return executor.run(command, spec.timeoutSeconds());
    }

    /** Extract the optional {@code argv} array argument as a {@code List<String>}, or null if absent. */
    private static List<String> argvArg(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "sandbox.run 'argv' must be a JSON array of strings, got: "
                  + value.getClass().getSimpleName());
        }
        List<String> argv = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                throw new IllegalArgumentException("sandbox.run 'argv' must not contain null elements.");
            }
            argv.add(element.toString());
        }
        return argv;
    }

    private static String stringOrNull(Object value) {
        return Optional.ofNullable(value).map(Object::toString).orElse(null);
    }
}
