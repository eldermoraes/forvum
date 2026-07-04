package ai.forvum.engine.plugin;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Owner-only, atomic installation of a resolved plugin JAR into {@code ~/.forvum/plugins/} — the write half
 * of {@code forvum plugin install <coords>} hardened for #171 (DR-6b [DP-10]). A JVM drop-in plugin executes
 * in-process with core-equivalent authority, so a locally writable {@code plugins/} directory or a
 * replaceable JAR is a code-injection point; the install therefore stages executable code owner-only and
 * never through a symlink.
 *
 * <p>Pure utility — no CDI, no reflection, pure {@code java.nio} (the same APIs {@code StateDirInitializer}
 * already runs inside the native image), so it is native-safe by construction. It carries this module's own
 * copy of the {@code POSIX}/{@code 0700}/{@code 0600} recipe rather than a shared helper (the copy-the-recipe
 * convention set by {@code InitCommand} ↔ {@code SkillInstaller} ↔ {@code StateDirInitializer}, [#173]).
 *
 * <p><strong>Install sequence.</strong> Reject a symlinked {@code pluginsDir} or a symlink planted at the
 * canonical target name (path substitution); create {@code pluginsDir} {@code 0700} on POSIX (owner-only by
 * construction, umask-independent) and repair a pre-existing loose directory; stream the bytes into a
 * {@code 0600}-at-birth temp file in the SAME directory and atomically {@link StandardCopyOption#ATOMIC_MOVE}
 * it onto the target, so a partially copied JAR is never exposed and a failed install never touches the
 * previously installed valid plugin.
 *
 * <p><strong>Fail policy (reject, not repair-and-warn).</strong> A symlinked {@code pluginsDir}/target, or a
 * pre-existing loose directory that cannot be tightened, FAILS the install with an actionable
 * {@link PluginInstallException}. This deliberately diverges from {@code StateDirInitializer}'s boot-time
 * repair-and-warn: that posture protects the M4 graceful-boot contract (CI native smoke, K8s PVC), none of
 * which applies to an interactive one-shot installer command staging executable code — fail-closed is the
 * correct posture (the closest install-path precedent, {@code SkillInstaller}, also fails on a chmod error).
 *
 * <p><strong>Non-POSIX.</strong> The directory and temp file are created without permission attributes and a
 * one-time warning notes that owner-only cannot be enforced; the install still completes atomically. The
 * strongest supported equivalent is the platform default access control.
 */
final class PluginArtifactInstaller {

    private static final Logger LOG = Logger.getLogger(PluginArtifactInstaller.class);

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private static volatile boolean nonPosixWarned;

    private PluginArtifactInstaller() {
    }

    /**
     * Install {@code resolvedJar} owner-only into {@code pluginsDir} under the resolver's canonical filename;
     * returns the installed path. See the class Javadoc for the sequence and fail policy.
     *
     * @throws PluginInstallException on an unsafe (symlinked / un-tightenable) directory or target, or a
     *         stream/move failure
     */
    static Path install(Path resolvedJar, Path pluginsDir) {
        return install(resolvedJar, pluginsDir, POSIX);
    }

    // Package-private overload with an explicit posix flag so the non-POSIX branch is exercised deterministically.
    static Path install(Path resolvedJar, Path pluginsDir, boolean posix) {
        // Reject a symlinked pluginsDir: following it would let an attacker redirect the staged executable JAR
        // outside the owner-only tree (path substitution). Never create or write through it.
        if (Files.isSymbolicLink(pluginsDir)) {
            throw new PluginInstallException("plugin directory " + pluginsDir + " is a symbolic link — "
                    + "refusing to install through it (possible path substitution); "
                    + "remove or replace it and re-run", null);
        }

        String jarName = resolvedJar.getFileName().toString();
        Path target = pluginsDir.resolve(jarName);
        Path tmp = null;
        try {
            ensureDir(pluginsDir, posix);
            // A symlink planted at the canonical target name is a planted redirect — refuse it rather than
            // replace-through (the atomic move onto a symlink would follow it and write the victim).
            if (Files.isSymbolicLink(target)) {
                throw new PluginInstallException("plugin target " + target + " is a symbolic link — "
                        + "refusing to replace it (possible path substitution); "
                        + "remove it and re-run", null);
            }
            // Stream the bytes into a 0600-at-birth temp file in the SAME directory (same filesystem, so the
            // move is atomic), then atomically replace the target. Streaming into the temp inode via a WRITE
            // OutputStream keeps the 0600 mode (unlike Files.copy(REPLACE_EXISTING), which recreates the file
            // with umask perms).
            tmp = createTemp(pluginsDir, jarName, posix);
            try (InputStream in = Files.newInputStream(resolvedJar);
                    OutputStream out = Files.newOutputStream(
                            tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null; // moved — nothing to clean up
            return target;
        } catch (IOException e) {
            throw new PluginInstallException(
                    "could not install the resolved plugin JAR into " + pluginsDir + ": " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    /**
     * Create {@code pluginsDir} owner-only ({@code 0700} on POSIX) if absent, and repair a pre-existing loose
     * directory to owner-only. A chmod that cannot be applied is fatal (fail-closed) — see the class Javadoc.
     */
    private static void ensureDir(Path pluginsDir, boolean posix) throws IOException {
        if (posix) {
            Files.createDirectories(pluginsDir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } else {
            Files.createDirectories(pluginsDir);
            warnNonPosixOnce();
        }
        // A TOCTOU swap could have resolved the path through a symlink established after the leaf check; if it
        // is not a real directory, refuse it rather than write the store through the link.
        if (!Files.isDirectory(pluginsDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PluginInstallException("plugin directory " + pluginsDir + " is not a directory — "
                    + "refusing to install into it", null);
        }
        if (posix) {
            // Repair a directory left group/world-accessible by a permissive umask on an earlier boot. Unlike
            // the boot path, a failure here is fatal: a world-writable plugins dir is a code-injection point.
            try {
                Files.setPosixFilePermissions(pluginsDir, DIR_PERMS);
            } catch (IOException | UnsupportedOperationException e) {
                throw new PluginInstallException("plugin directory " + pluginsDir + " has unsafe permissions "
                        + "that could not be tightened to owner-only (" + e.getMessage() + ") — "
                        + "chmod 700 / chown it, or remove it, and re-run", e);
            }
        }
    }

    /** A {@code .<jarName>.tmp} temp file in {@code pluginsDir}, born {@code 0600} on POSIX. */
    private static Path createTemp(Path pluginsDir, String jarName, boolean posix) throws IOException {
        if (posix) {
            return Files.createTempFile(pluginsDir, "." + jarName, ".tmp",
                    PosixFilePermissions.asFileAttribute(FILE_PERMS));
        }
        return Files.createTempFile(pluginsDir, "." + jarName, ".tmp");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warnf("Could not clean up the plugin install temp file (%s)", e.getMessage());
        }
    }

    private static void warnNonPosixOnce() {
        if (!nonPosixWarned) {
            nonPosixWarned = true;
            LOG.warn("Filesystem does not support POSIX permissions — owner-only (0700/0600) protection of "
                    + "the plugin directory cannot be enforced; relying on the platform default access control");
        }
    }
}
