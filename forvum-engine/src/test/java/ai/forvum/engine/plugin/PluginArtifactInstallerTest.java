package ai.forvum.engine.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Drives {@link PluginArtifactInstaller} directly (plain {@code *Test}, no Quarkus boot) — the owner-only,
 * atomic, symlink-rejecting install seam #171 adds. POSIX-mode assertions are gated with {@code assumeTrue}
 * on the platform capability; the non-POSIX branch is exercised deterministically via the
 * {@code install(..., boolean posix)} overload (the [#173] "non-POSIX branch is a coverage guard, not
 * red-drivable on a POSIX host" pattern).
 */
class PluginArtifactInstallerTest {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_0700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_0600 = PosixFilePermissions.fromString("rw-------");

    private static final String JAR_NAME = "tiny-plugin-1.0.0.jar";
    private static final String JAR_BYTES = "plugin-bytes";

    @Test
    void createsThePluginsDirOwnerOnlyAndInstalledJarOwnerOnly(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "owner-only permission assertions require a POSIX filesystem");
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path plugins = tmp.resolve("plugins");

        Path installed = PluginArtifactInstaller.install(resolved, plugins);

        assertEquals(DIR_0700, Files.getPosixFilePermissions(plugins),
                "the plugins dir must be created owner-only (0700)");
        assertEquals(FILE_0600, Files.getPosixFilePermissions(installed),
                "the installed JAR must be owner-only (0600)");
        assertEquals(JAR_BYTES, Files.readString(installed), "the installed JAR content must be intact");
        // No .tmp residue: the directory listing is exactly the installed JAR.
        assertEquals(List.of(installed.getFileName().toString()), listing(plugins),
                "the plugins dir must contain exactly the installed JAR, no temp residue");
    }

    @Test
    void repairsALoosePreexistingPluginsDir(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "permission repair requires a POSIX filesystem");
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(plugins);
        Files.setPosixFilePermissions(plugins, PosixFilePermissions.fromString("rwxr-xr-x")); // 0755, loose

        PluginArtifactInstaller.install(resolved, plugins);

        assertEquals(DIR_0700, Files.getPosixFilePermissions(plugins),
                "a pre-existing loose plugins dir must be tightened to owner-only (0700)");
    }

    @Test
    void rejectsASymlinkedPluginsDir(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "symlink rejection requires a POSIX filesystem");
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path victim = tmp.resolve("victim-dir");
        Files.createDirectories(victim);
        Path plugins = tmp.resolve("plugins-link");
        Files.createSymbolicLink(plugins, victim);

        assertThrows(PluginInstallException.class, () -> PluginArtifactInstaller.install(resolved, plugins),
                "a symlinked plugins dir is a path-substitution vector and must be rejected");
        // The victim directory the link pointed at must be untouched (no JAR written through it).
        assertTrue(listing(victim).isEmpty(),
                () -> "the link target must not be written through; saw: " + listing(victim));
    }

    @Test
    void rejectsASymlinkedExistingTargetJar(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "symlink rejection requires a POSIX filesystem");
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(plugins);
        Path victim = tmp.resolve("victim.txt");
        Files.writeString(victim, "victim-content");
        // A symlink planted at the canonical target name pointing at a victim file.
        Files.createSymbolicLink(plugins.resolve(JAR_NAME), victim);

        assertThrows(PluginInstallException.class, () -> PluginArtifactInstaller.install(resolved, plugins),
                "a symlink planted at the target JAR name must be rejected, never replaced-through");
        assertEquals("victim-content", Files.readString(victim),
                "the symlink target file must be untouched");
    }

    @Test
    void reinstallReplacesTheJarAndKeepsOwnerOnly(@TempDir Path tmp) throws IOException {
        assumeTrue(POSIX, "owner-only permission assertions require a POSIX filesystem");
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(plugins);
        Path target = plugins.resolve(JAR_NAME);
        Files.writeString(target, "old-content");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--")); // 0644, loose

        Path installed = PluginArtifactInstaller.install(resolved, plugins);

        assertEquals(target, installed, "a re-install must overwrite the canonical target");
        assertEquals(JAR_BYTES, Files.readString(installed), "the re-installed JAR must carry the new bytes");
        assertEquals(FILE_0600, Files.getPosixFilePermissions(installed),
                "a re-installed JAR must be owner-only (0600), repairing the old loose perms");
    }

    @Test
    void failedInstallLeavesThePreviousJarUntouched(@TempDir Path tmp) throws IOException {
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(plugins);
        Path target = plugins.resolve(JAR_NAME);
        Files.writeString(target, "previous-valid-content");
        // A resolvedJar that does not exist: the stream-into fails after (or before) temp creation.
        Path missing = tmp.resolve("nonexistent.jar").resolve(JAR_NAME);

        assertThrows(PluginInstallException.class, () -> PluginArtifactInstaller.install(missing, plugins),
                "installing from an unreadable source must fail");
        assertEquals("previous-valid-content", Files.readString(target),
                "a failed install must not alter the previously installed valid plugin");
        // No .tmp residue from the aborted install.
        assertTrue(listing(plugins).stream().noneMatch(n -> n.endsWith(".tmp")),
                () -> "a failed install must clean up its temp file; saw: " + listing(plugins));
    }

    @Test
    void nonPosixBranchStillInstallsWithoutPermEnforcement(@TempDir Path tmp) throws IOException {
        // On a POSIX host, force the non-POSIX branch via the overload: the JAR still lands, no exception,
        // no perm attrs applied (a coverage guard — its correctness is indistinguishable from unimplemented
        // on a POSIX host, so the exact-mode assertions above are the real drivers).
        Path resolved = writeJar(tmp.resolve(JAR_NAME));
        Path plugins = tmp.resolve("plugins");

        Path installed = PluginArtifactInstaller.install(resolved, plugins, false);

        assertTrue(Files.exists(installed), "the non-POSIX branch must still install the JAR");
        assertEquals(JAR_BYTES, Files.readString(installed), "the installed JAR content must be intact");
    }

    private static Path writeJar(Path path) throws IOException {
        Files.writeString(path, JAR_BYTES);
        return path;
    }

    private static List<String> listing(Path dir) {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not list " + dir, e);
        }
    }
}
