package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.runtime.CommandMode;

import io.quarkus.runtime.StartupEvent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The M20 cold-start lever in {@link PersistenceBootstrap}: a one-shot command skips Flyway migration,
 * while a normal interactive/server run still migrates. <strong>Both</strong> directions are asserted so
 * that dropping the {@code commandMode.isOneShot()} guard (review finding #15) fails the build. Pure JVM —
 * a recording {@link Flyway} stand-in, no real database, no Quarkus boot.
 */
class PersistenceBootstrapTest {

    @Test
    void oneShotCommandSkipsFlywayMigration(@TempDir Path tmp) {
        RecordingFlyway flyway = new RecordingFlyway();
        PersistenceBootstrap bootstrap = new PersistenceBootstrap(
                new ForvumHome(Optional.of(tmp.toString())), flyway, new CommandMode(new String[] {"--help"}));

        bootstrap.onStart(new StartupEvent());

        assertFalse(flyway.migrated, "a one-shot command must not migrate the schema");
    }

    @Test
    void normalRunMigratesTheSchema(@TempDir Path tmp) {
        RecordingFlyway flyway = new RecordingFlyway();
        PersistenceBootstrap bootstrap = new PersistenceBootstrap(
                new ForvumHome(Optional.of(tmp.toString())), flyway, new CommandMode(new String[] {}));

        bootstrap.onStart(new StartupEvent());

        assertTrue(flyway.migrated, "a normal interactive/server run must migrate the schema");
    }

    /** A {@link Flyway} that records whether {@code migrate()} was invoked, without touching a database. */
    private static final class RecordingFlyway extends Flyway {

        private boolean migrated;

        RecordingFlyway() {
            super(Flyway.configure());
        }

        @Override
        public MigrateResult migrate() {
            migrated = true;
            return null;
        }
    }
}
