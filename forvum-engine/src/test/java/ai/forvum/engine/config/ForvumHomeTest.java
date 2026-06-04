package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ForvumHome#resolve(Optional, String)} — the pure precedence of the configured
 * home over the {@code <user.home>/.forvum} default. The system-property/env sourcing of
 * {@code forvum.home} is MicroProfile Config's responsibility and is not re-tested here. Pure
 * {@code *Test} — no Quarkus boot.
 */
class ForvumHomeTest {

    @Test
    void configuredHomeWinsOverDefault() {
        assertEquals(Path.of("/tmp/forvum-cfg").toAbsolutePath().normalize(),
                ForvumHome.resolve(Optional.of("/tmp/forvum-cfg"), "/home/u"));
    }

    @Test
    void fallsBackToUserHomeDotForvumWhenAbsent() {
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(),
                ForvumHome.resolve(Optional.empty(), "/home/u"));
    }

    @Test
    void blankConfiguredHomeIsIgnored() {
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(),
                ForvumHome.resolve(Optional.of("   "), "/home/u"));
    }

    @Test
    void resolvedRootIsAbsoluteAndNormalized() {
        Path resolved = ForvumHome.resolve(Optional.of("/tmp/a/../forvum"), "/home/u");

        assertTrue(resolved.isAbsolute(), "resolved root must be absolute");
        assertEquals(Path.of("/tmp/forvum"), resolved);
    }
}
