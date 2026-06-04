package ai.forvum.engine.persistence;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Best-effort creation of {@code $FORVUM_HOME/state} (where the SQLite database lives).
 *
 * <p>Pure utility — no CDI. {@link PersistenceBootstrap} calls this at startup before triggering the
 * Flyway migration, because SQLite creates the database <em>file</em> on connect but not its parent
 * directory. Mirrors the M4 graceful-boot contract: a failure (e.g. a read-only {@code $FORVUM_HOME}
 * in the CI native smoke that runs with no {@code ~/.forvum/}) is logged, not thrown — the caller
 * then skips migration rather than crashing the boot.
 */
final class StateDirInitializer {

    private static final Logger LOG = Logger.getLogger(StateDirInitializer.class);

    private StateDirInitializer() {
    }

    /**
     * Ensures {@code stateDir} exists. Never throws.
     *
     * @return {@code true} if the directory exists after the call, {@code false} if it could not be
     *         created (in which case persistence should be treated as unavailable for this run).
     */
    static boolean ensureStateDir(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            return true;
        } catch (IOException e) {
            LOG.warnf("Could not create state directory %s (%s) — persistence may be unavailable",
                    stateDir, e.getMessage());
            return Files.isDirectory(stateDir);
        }
    }
}
