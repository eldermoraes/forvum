package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Coverage of {@link ShellAllowlist}'s home resolution (the {@code @Inject} constructor path and the
 * {@link ShellAllowlist#resolveHome(Optional, String)} helper, which mirrors {@code ForvumHome.resolve}):
 * a configured non-blank {@code forvum.home}, a present-but-blank value (falls back), and an absent value
 * (falls back to {@code <user.home>/.forvum}).
 */
class ShellAllowlistHomeTest {

    @Test
    void resolveHomeUsesAConfiguredNonBlankValue(@TempDir Path dir) {
        Path home = ShellAllowlist.resolveHome(Optional.of(dir.toString()), "/ignored");

        assertEquals(dir.toAbsolutePath().normalize(), home,
                "a configured non-blank forvum.home is used directly (absolute, normalized)");
    }

    @Test
    void resolveHomeFallsBackToUserHomeWhenConfiguredValueIsBlank(@TempDir Path dir) {
        Path home = ShellAllowlist.resolveHome(Optional.of("   "), dir.toString());

        assertEquals(dir.resolve(ShellAllowlist.DEFAULT_HOME_DIR).toAbsolutePath().normalize(), home,
                "a present-but-blank forvum.home falls back to <user.home>/.forvum");
    }

    @Test
    void resolveHomeFallsBackToUserHomeWhenAbsent(@TempDir Path dir) {
        Path home = ShellAllowlist.resolveHome(Optional.empty(), dir.toString());

        assertEquals(dir.resolve(ShellAllowlist.DEFAULT_HOME_DIR).toAbsolutePath().normalize(), home,
                "an absent forvum.home falls back to <user.home>/.forvum");
    }

    @Test
    void injectConstructorResolvesShellJsonUnderTheConfiguredHome(@TempDir Path dir) {
        // Exercise the @Inject constructor (config-driven path) rather than the package-private file ctor:
        // with a configured home, an absent tools/shell.json reads fail-closed.
        ShellAllowlist allowlist = new ShellAllowlist(Optional.of(dir.toString()));

        ShellAllowlist.Spec spec = allowlist.read();

        assertTrue(spec.allowedCommands().isEmpty(),
                "the @Inject constructor resolves tools/shell.json under the configured home; absent => fail-closed");
    }
}
