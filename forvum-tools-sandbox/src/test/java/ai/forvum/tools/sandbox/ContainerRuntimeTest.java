package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * Pure detection tests for {@link ContainerRuntime#resolve(Optional, String)}: prefer podman, fall back to
 * docker, fail-closed when neither is present, and honor an operator-pinned runtime. The fake executables
 * are created in a {@code @TempDir} and the PATH is synthetic, so no real podman/docker is required.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX executable-bit fixtures")
class ContainerRuntimeTest {

    /** Create an executable file named {@code name} under {@code dir}. */
    private static Path fakeExecutable(Path dir, String name) throws IOException {
        Path bin = dir.resolve(name);
        Files.writeString(bin, "#!/bin/sh\n");
        Files.setPosixFilePermissions(bin, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.OWNER_WRITE));
        return bin;
    }

    @Test
    void prefersPodmanWhenBothArePresent(@TempDir Path dir) throws IOException {
        fakeExecutable(dir, "podman");
        fakeExecutable(dir, "docker");

        Optional<String> resolved = ContainerRuntime.resolve(Optional.empty(), dir.toString());

        assertEquals(Optional.of("podman"), resolved, "podman wins over docker");
    }

    @Test
    void fallsBackToDockerWhenPodmanIsAbsent(@TempDir Path dir) throws IOException {
        fakeExecutable(dir, "docker");

        Optional<String> resolved = ContainerRuntime.resolve(Optional.empty(), dir.toString());

        assertEquals(Optional.of("docker"), resolved, "docker is the fallback runtime");
    }

    @Test
    void isFailClosedWhenNeitherRuntimeIsPresent(@TempDir Path dir) {
        // The temp dir (the only PATH entry) holds no podman/docker, so detection is deterministically
        // fail-closed — detection searches ONLY the supplied PATH (no host-dir augmentation), so a runtime
        // installed on the developer's box cannot leak into this hermetic assertion.
        Optional<String> resolved = ContainerRuntime.resolve(Optional.empty(), dir.toString());

        assertTrue(resolved.isEmpty(), "no runtime on the synthetic PATH resolves to empty, got: " + resolved);
    }

    @Test
    void aBlankPathResolvesEmpty() {
        assertTrue(ContainerRuntime.resolve(Optional.empty(), "").isEmpty(),
                "an empty PATH and no host augmentation means no runtime is found");
        assertTrue(ContainerRuntime.resolve(Optional.empty(), null).isEmpty(),
                "a null PATH is handled and resolves empty");
    }

    @Test
    void honorsAnOperatorPinnedBareName(@TempDir Path dir) throws IOException {
        fakeExecutable(dir, "nerdctl");

        Optional<String> resolved = ContainerRuntime.resolve(Optional.of("nerdctl"), dir.toString());

        assertEquals(Optional.of("nerdctl"), resolved, "an operator-pinned runtime bypasses auto-detection");
    }

    @Test
    void honorsAnOperatorPinnedAbsolutePath(@TempDir Path dir) throws IOException {
        Path bin = fakeExecutable(dir, "podman");

        Optional<String> resolved = ContainerRuntime.resolve(Optional.of(bin.toString()), "/nonexistent");

        assertEquals(Optional.of(bin.toString()), resolved,
                "an absolute pinned path is honored verbatim, regardless of PATH");
    }

    @Test
    void aBlankPinnedRuntimeFallsBackToAutoDetection(@TempDir Path dir) throws IOException {
        fakeExecutable(dir, "podman");

        Optional<String> resolved = ContainerRuntime.resolve(Optional.of("  "), dir.toString());

        assertEquals(Optional.of("podman"), resolved, "a blank pin is ignored, auto-detection applies");
    }

    @Test
    void aPinnedAbsolutePathThatDoesNotExistResolvesEmpty() {
        Optional<String> resolved =
                ContainerRuntime.resolve(Optional.of("/no/such/runtime/podman"), "/nonexistent");

        assertTrue(resolved.isEmpty(), "a pinned path that is not an executable file is not resolvable");
    }
}
