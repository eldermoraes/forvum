package ai.forvum.tools.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Home resolution + on-demand file read for {@link SandboxConfig}: it mirrors {@code ForvumHome.resolve}
 * (the {@code forvum.home} MP Config property, else {@code <user.home>/.forvum}) and reads
 * {@code tools/sandbox.json} per call, fail-closed when absent.
 */
class SandboxConfigHomeTest {

    @Test
    void resolveHomeUsesTheConfiguredValueWhenPresent() {
        Path home = SandboxConfig.resolveHome(Optional.of("/srv/forvum"), "/home/ignored");
        assertEquals(Path.of("/srv/forvum").toAbsolutePath().normalize(), home);
    }

    @Test
    void resolveHomeFallsBackToUserHomeWhenBlankOrAbsent() {
        Path expected = Path.of("/home/bob").resolve(".forvum").toAbsolutePath().normalize();
        assertEquals(expected, SandboxConfig.resolveHome(Optional.empty(), "/home/bob"));
        assertEquals(expected, SandboxConfig.resolveHome(Optional.of("  "), "/home/bob"));
    }

    @Test
    void readIsFailClosedWhenTheFileIsAbsent(@TempDir Path home) {
        SandboxConfig config = new SandboxConfig(home.resolve("tools").resolve("sandbox.json"));

        assertTrue(config.read().image().isBlank(),
                "with no tools/sandbox.json the config is fail-closed (no image)");
    }

    @Test
    void readParsesAPresentFile(@TempDir Path home) throws IOException {
        Path file = home.resolve("tools").resolve("sandbox.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"image\":\"python:3.12-slim\",\"timeoutSeconds\":42}");
        SandboxConfig config = new SandboxConfig(file);

        SandboxConfig.Spec spec = config.read();

        assertEquals("python:3.12-slim", spec.image());
        assertEquals(42, spec.timeoutSeconds());
    }

    @Test
    void readThrowsOnAMalformedFile(@TempDir Path home) throws IOException {
        Path file = home.resolve("tools").resolve("sandbox.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not valid json ");
        SandboxConfig config = new SandboxConfig(file);

        assertThrows(UncheckedIOException.class, config::read,
                "a malformed config is a real misconfiguration the operator must see");
    }
}
