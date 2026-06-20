package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live acceptance for {@code sandbox.run}: drives a REAL container through the provider end-to-end. This is
 * the only test that needs a container runtime (podman/docker), so it is {@code @Tag("live")} (the CLAUDE.md
 * §4/§11 default-off-in-CI convention) AND it SKIPS itself via JUnit {@link org.junit.jupiter.api.Assumptions}
 * when no runtime is present — so the default suite stays hermetic both on a CI cell with no runtime and on a
 * developer/CI machine that DOES have podman/docker but runs a plain {@code verify}. To run it locally:
 * install podman (or docker) and {@code podman pull busybox:latest}, then
 * {@code ./mvnw -pl forvum-tools-sandbox test}.
 *
 * <p>The two acceptance properties it proves on a real container:
 * <ol>
 *   <li><strong>A sandboxed run returns output.</strong> A snippet's stdout reaches the model.</li>
 *   <li><strong>An escape attempt is contained.</strong> With the default {@code --network=none}, outbound
 *       network is unreachable from inside the container, so a network probe fails (non-zero exit).</li>
 * </ol>
 */
@Tag("live")
class SandboxRunLiveTest {

    private static final String IMAGE = "busybox:latest";

    /** The first runtime present, or skip the test. */
    private static String runtimeOrSkip() {
        Optional<String> runtime = ContainerRuntime.resolve(Optional.empty(), System.getenv("PATH"));
        assumeTrue(runtime.isPresent(), "no container runtime (podman/docker) present — skipping live test");
        return runtime.get();
    }

    /** Whether the runtime can actually pull/run the image (a sandbox/socket-less CI cannot). */
    private static void assumeImageRunnable(String runtime) {
        try {
            Process probe = new ProcessBuilder(runtime, "run", "--rm", IMAGE, "true")
                    .redirectErrorStream(true).start();
            probe.getOutputStream().close();
            boolean done = probe.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                probe.destroyForcibly();
            }
            assumeTrue(done && probe.exitValue() == 0,
                    "the container runtime cannot run " + IMAGE + " here — skipping live test");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            assumeTrue(false, "container runtime probe failed: " + e.getMessage());
        }
    }

    private static SandboxToolProvider providerFor(Path home, Path workspace) throws IOException {
        Path file = home.resolve("tools").resolve("sandbox.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"image\":\"" + IMAGE + "\",\"timeoutSeconds\":120}");
        SandboxToolProvider provider = new SandboxToolProvider();
        provider.config = new SandboxConfig(file);
        provider.workspace = new WorkspaceRoot(workspace);
        return provider;
    }

    @Test
    void aSandboxedRunReturnsOutput(@TempDir Path home, @TempDir Path workspace) throws IOException {
        String runtime = runtimeOrSkip();
        assumeImageRunnable(runtime);
        SandboxToolProvider provider = providerFor(home, workspace);

        String out = provider.invoke("sandbox.run",
                Map.of("argv", List.of("echo", "hello from the sandbox")));

        assertTrue(out.contains("hello from the sandbox"),
                "the container's stdout reaches the model, got: " + out);
    }

    @Test
    void networkEgressIsContainedByDefault(@TempDir Path home, @TempDir Path workspace) throws IOException {
        String runtime = runtimeOrSkip();
        assumeImageRunnable(runtime);
        SandboxToolProvider provider = providerFor(home, workspace);

        // With --network=none, even DNS/connect is impossible inside the container; `nslookup`/`wget` fails
        // non-zero, which the executor surfaces as a SandboxExecException. The escape attempt is contained.
        assertThrows(SandboxExecException.class,
                () -> provider.invoke("sandbox.run",
                        Map.of("argv", List.of("wget", "-q", "-T", "5", "-O", "-", "http://example.com"))),
                "outbound network is unreachable with the default --network=none");
    }

    @Test
    void aHostPathTraversalNeverReachesTheRuntime(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        // No runtime needed: the workspace confinement rejects the escape before any container launch. This
        // runs even without a runtime (no assumeTrue) — it proves the host-traversal containment.
        SandboxToolProvider provider = providerFor(home, workspace);

        assertThrows(WorkspaceEscapeException.class,
                () -> provider.invoke("sandbox.run",
                        Map.of("argv", List.of("ls"), "workingDir", "../../etc")),
                "a host path traversal is rejected before the container is built");
    }
}
