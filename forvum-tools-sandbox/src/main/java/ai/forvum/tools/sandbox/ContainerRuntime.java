package ai.forvum.tools.sandbox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects the container runtime that launches a {@code sandbox.run} ephemeral container (ULTRAPLAN §9.2.5,
 * #27). Forvum prefers {@code podman} (the host's runtime, rootless by default) and falls back to
 * {@code docker}; if neither is found the sandbox is fail-closed (no execution).
 *
 * <p>Pure (Quarkus-free, no CDI) so it is directly unit-testable with a synthetic PATH. Detection resolves
 * the FIRST of the candidates that exists as an executable file on the supplied PATH — the same
 * {@code PATH} the {@code SandboxExecutor} passes through to the runtime, so a bare-name match here resolves
 * identically at exec time. An operator may pin the runtime explicitly via {@code tools/sandbox.json} (the
 * {@code runtime} key), which bypasses auto-detection.
 */
public final class ContainerRuntime {

    /** Auto-detection order: podman first (rootless, host preference), then docker. */
    static final List<String> DEFAULT_CANDIDATES = List.of("podman", "docker");

    private ContainerRuntime() {
    }

    /**
     * Resolve the container runtime executable. When {@code pinned} is present it is honored verbatim (a
     * bare name or an absolute path); otherwise {@link #DEFAULT_CANDIDATES} are probed in order. Returns the
     * resolved invocation token (a bare name found on PATH, or an absolute path) or empty if none is
     * available.
     *
     * @param pinned   the operator-configured runtime from {@code tools/sandbox.json}, or empty to auto-detect
     * @param pathEnv  the {@code PATH} to search (the scrubbed PATH the runtime will run with)
     */
    static Optional<String> resolve(Optional<String> pinned, String pathEnv) {
        List<String> candidates = pinned
                .filter(value -> !value.isBlank())
                .map(value -> List.of(value.strip()))
                .orElse(DEFAULT_CANDIDATES);
        for (String candidate : candidates) {
            Optional<String> found = locate(candidate, pathEnv);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Locate a single candidate (absolute path used as-is; a bare name searched on the supplied PATH). */
    private static Optional<String> locate(String candidate, String pathEnv) {
        if (candidate.indexOf(File.separatorChar) >= 0 || candidate.startsWith("/")) {
            // An absolute (or path-bearing) candidate: honored verbatim if it is an executable file. The
            // returned token is the absolute path, so the runtime is invoked directly.
            return isExecutableFile(Path.of(candidate)) ? Optional.of(candidate) : Optional.empty();
        }
        for (Path dir : searchDirs(pathEnv)) {
            if (isExecutableFile(dir.resolve(candidate))) {
                // A bare name resolved on PATH: return the bare name so ProcessBuilder resolves it the same
                // way at exec time (consistent with how the scrubbed-PATH child sees it).
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** The directories to search: the PATH entries. */
    private static List<Path> searchDirs(String pathEnv) {
        List<Path> dirs = new ArrayList<>();
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String entry : pathEnv.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    dirs.add(Path.of(entry));
                }
            }
        }
        return dirs;
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }
}
