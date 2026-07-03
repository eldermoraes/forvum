package ai.forvum.engine.persistence;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Best-effort creation and owner-only hardening of {@code $FORVUM_HOME/state} (where the SQLite database
 * lives). Pure utility — no CDI. {@link PersistenceBootstrap} calls this at startup before triggering the
 * Flyway migration, because SQLite creates the database <em>file</em> on connect but not its parent
 * directory.
 *
 * <p><strong>Owner-only posture (#173, DR-6c [6c-DP-13]).</strong> The state tree carries the ledger,
 * conversation history, tool arguments, approvals, CAPR verdicts, and memory, so it must not inherit a
 * permissive process umask when the operator launches without first running {@code forvum init}. On POSIX
 * this mirrors the {@code InitCommand} recipe: {@code state/} is created {@code 0700} independent of umask
 * (owner-only perms carry no group/other bits for the umask to strip), a pre-existing world-accessible
 * {@code state/} is repaired, and the database + WAL/SHM sidecars are tightened to {@code 0600} after
 * migration. The {@code 0700} directory is the durable guarantee — it contains, by traversal permission,
 * every file inside (including sidecars SQLite recreates after boot) regardless of their individual mode.
 *
 * <p><strong>Fail policy (repair-and-warn, option A).</strong> Enforcement is best-effort and never blocks
 * the boot: mirroring the M4 graceful-boot contract, a failure to create or tighten permissions (a
 * read-only {@code $FORVUM_HOME}, a group-shared Kubernetes volume, not being the owner) is logged and the
 * run continues rather than crashing — preserving the CI native smoke (which runs with no {@code ~/.forvum/})
 * and container/PVC deployments. A symlink where {@code state/} is expected is the one hard-reject: it is a
 * path-substitution vector and is never created or hardened through. Permission-failure warnings deliberately
 * omit the absolute state path ([6c-DP-14]). On a non-POSIX filesystem the method creates the directory and
 * warns once that owner-only guarantees cannot be established.
 */
final class StateDirInitializer {

    private static final Logger LOG = Logger.getLogger(StateDirInitializer.class);

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private static volatile boolean nonPosixWarned;

    private StateDirInitializer() {
    }

    /**
     * Ensures {@code stateDir} exists and is owner-only ({@code 0700} on POSIX). Never throws.
     *
     * @return {@code true} if the directory exists after the call, {@code false} if it could not be created
     *         or is a symlink (in which case persistence should be treated as unavailable for this run).
     */
    static boolean ensureStateDir(Path stateDir) {
        return ensureStateDir(stateDir, POSIX);
    }

    // Package-private overload with an explicit posix flag so the non-POSIX branch is exercised deterministically.
    static boolean ensureStateDir(Path stateDir, boolean posix) {
        // Reject a symlink where state/ is expected: following it would let an attacker redirect the SQLite
        // store (and its sensitive rows) outside the owner-only tree. Treat persistence as unavailable.
        if (Files.isSymbolicLink(stateDir)) {
            LOG.warn("State directory is a symbolic link — refusing to use it (possible path substitution); "
                    + "persistence is disabled for this run");
            return false;
        }
        try {
            if (posix) {
                Files.createDirectories(stateDir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
            } else {
                Files.createDirectories(stateDir);
                warnNonPosixOnce();
            }
        } catch (IOException e) {
            LOG.warnf("Could not create the state directory (%s) — persistence may be unavailable",
                    e.getMessage());
            return Files.isDirectory(stateDir, LinkOption.NOFOLLOW_LINKS);
        }
        // NOFOLLOW here mirrors hardenStateFiles: if the path resolved through a symlink established after
        // the leaf check above (a TOCTOU swap), it is not a real directory — refuse it rather than harden
        // and write the store through the link.
        if (!Files.isDirectory(stateDir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        // Repair: a state/ left group/world-accessible by a permissive umask on an earlier boot is tightened
        // to owner-only. Best-effort (option A) — harden() warns and continues if the chmod cannot be applied.
        if (posix) {
            harden(stateDir, DIR_PERMS);
        }
        return true;
    }

    /**
     * Best-effort owner-only hardening of everything under {@code stateDir}: regular files to {@code 0600},
     * directories to {@code 0700}. Called after Flyway creates the SQLite database so the store, its WAL/SHM
     * sidecars, and any nested state (e.g. {@code credentials/}) are owner-only. Symlinks are never followed.
     * Never throws.
     */
    static void hardenStateFiles(Path stateDir) {
        hardenStateFiles(stateDir, POSIX);
    }

    // Package-private overload with an explicit posix flag (see ensureStateDir).
    static void hardenStateFiles(Path stateDir, boolean posix) {
        if (!posix || !Files.isDirectory(stateDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(stateDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    harden(dir, DIR_PERMS);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // walkFileTree does not follow links, so a symlink surfaces here — never chmod through it.
                    if (!attrs.isSymbolicLink()) {
                        harden(file, FILE_PERMS);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // best-effort: a single unreadable entry never aborts the pass
                }
            });
        } catch (IOException e) {
            LOG.warnf("Could not harden state file permissions (%s)", e.getMessage());
        }
    }

    private static void harden(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warnf("Could not enforce owner-only permissions on a state path (%s) — "
                    + "it may be accessible to other local users", e.getMessage());
        }
    }

    private static void warnNonPosixOnce() {
        if (!nonPosixWarned) {
            nonPosixWarned = true;
            LOG.warn("Filesystem does not support POSIX permissions — owner-only (0700/0600) protection of "
                    + "runtime state cannot be enforced; relying on the platform default access control");
        }
    }
}
