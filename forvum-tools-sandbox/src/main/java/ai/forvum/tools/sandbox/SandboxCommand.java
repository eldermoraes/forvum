package ai.forvum.tools.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder of the container-runtime invocation for {@code sandbox.run} (ULTRAPLAN §9.2.5, #27). Given
 * the detected {@code runtime}, the {@link SandboxConfig.Spec}, the confined host workspace directory, and
 * the request (code snippet OR an explicit argv), it assembles the LIST-form {@code <runtime> run …} argv
 * the {@link SandboxExecutor} launches. No process is started here, so the whole hardening surface is
 * unit-testable without a container runtime.
 *
 * <p>Hardening (the default-deny container posture):
 * <ul>
 *   <li>{@code --rm} — the container is ephemeral; it is removed on exit.</li>
 *   <li>{@code --network=none} (unless {@code allowNetwork}) — no egress, so a snippet cannot exfiltrate or
 *       reach a private host even if it tries.</li>
 *   <li>{@code --read-only} root fs + a {@code --tmpfs /tmp} — the image is immutable; only the explicit
 *       workspace mount (and a small tmpfs) is writable.</li>
 *   <li>{@code --cap-drop=ALL} + {@code --security-opt=no-new-privileges} — no Linux capabilities, no
 *       setuid escalation.</li>
 *   <li>{@code --user <uid:gid>} — a non-root in-container user (default {@code 1000:1000}).</li>
 *   <li>{@code --cpus}/{@code --memory}/{@code --pids-limit} — bounded CPU, memory, and process count.</li>
 *   <li>The host workspace is bind-mounted read-only at the container workdir by default; the model opts
 *       into a writable mount per call ({@code writable=true}).</li>
 * </ul>
 *
 * <p>The host-path traversal containment is the caller's job ({@link WorkspaceRoot#confine(String)}): only
 * a real path under the workspace root is ever passed here as {@code hostWorkdir}, so the bind-mount source
 * cannot point at the host root or a sibling of the workspace.
 */
public final class SandboxCommand {

    /** A conservative process-count cap; a sandboxed snippet has no reason to fork-bomb. */
    static final String PIDS_LIMIT = "256";

    private SandboxCommand() {
    }

    /**
     * Build the full {@code <runtime> run …} argv. Exactly one of {@code code} / {@code argv} is the payload:
     * {@code code} (non-null) runs through the configured interpreter; otherwise {@code argv} (non-empty) is
     * exec'd directly in the container.
     *
     * @param runtime     the resolved runtime token (e.g. {@code podman} or an absolute path)
     * @param spec        the operator config (image, limits, interpreter, …)
     * @param hostWorkdir the confined, real host directory bind-mounted into the container
     * @param code        the code snippet to run via the interpreter, or {@code null} for argv mode
     * @param argv        the explicit in-container argv, or {@code null}/empty for code mode
     * @param writable    whether the workspace mount is read-write (default read-only)
     * @throws SandboxExecException if neither a code snippet nor an argv vector is supplied
     */
    static List<String> build(String runtime, SandboxConfig.Spec spec, Path hostWorkdir,
                              String code, List<String> argv, boolean writable) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasArgv = argv != null && !argv.isEmpty();
        if (hasCode == hasArgv) {
            throw new SandboxExecException(
                    "sandbox.run requires exactly one of 'code' (a snippet for the interpreter) or 'argv' "
                  + "(an explicit in-container command vector).");
        }

        List<String> command = new ArrayList<>();
        command.add(runtime);
        command.add("run");
        command.add("--rm");
        // Non-interactive: never allocate a TTY and never read host stdin into the container.
        command.add("--network");
        command.add(spec.allowNetwork() ? "bridge" : networkMode(spec));
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,size=64m");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges");
        command.add("--user");
        command.add(spec.user());
        command.add("--cpus");
        command.add(spec.cpus());
        command.add("--memory");
        command.add(spec.memory());
        command.add("--pids-limit");
        command.add(PIDS_LIMIT);
        command.add("--workdir");
        command.add(spec.containerWorkdir());
        // Bind-mount the confined host workspace; read-only by default (the model opts into rw per call).
        command.add("--volume");
        command.add(hostWorkdir + ":" + spec.containerWorkdir() + (writable ? ":rw" : ":ro"));
        command.add(spec.image());

        if (hasCode) {
            command.addAll(spec.interpreter());
            command.add(code);
        } else {
            command.addAll(argv);
        }
        return List.copyOf(command);
    }

    /**
     * The {@code --network} value when egress is denied. The operator-configured {@code network} (default
     * {@code none}) is used; a blank or whitespace value falls back to {@code none} so a misconfiguration
     * never silently opens the network.
     */
    private static String networkMode(SandboxConfig.Spec spec) {
        String configured = spec.network();
        return configured == null || configured.isBlank() ? SandboxConfig.DEFAULT_NETWORK : configured.strip();
    }
}
