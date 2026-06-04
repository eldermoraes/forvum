package ai.forvum.engine.persistence;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import ai.forvum.engine.config.ForvumHome;

/**
 * Brings the persistence layer up at startup: ensures {@code $FORVUM_HOME/state} exists, then runs the
 * Flyway migration against the SQLite database.
 *
 * <p>Quarkus' {@code quarkus.flyway.migrate-at-start} is deliberately off (see application.properties):
 * it migrates during RUNTIME_INIT, before any {@link StartupEvent} observer, which would open the
 * SQLite file before its parent directory exists. Doing it here — after {@link
 * StateDirInitializer#ensureStateDir} — guarantees the directory is in place first, and lets the boot
 * degrade gracefully (warn + skip) when the home is unwritable (the CI native smoke runs with no
 * {@code ~/.forvum/}), per the M4 lesson.
 */
@Singleton
public class PersistenceBootstrap {

    private static final Logger LOG = Logger.getLogger(PersistenceBootstrap.class);

    private final ForvumHome home;
    private final Flyway flyway;

    @Inject
    public PersistenceBootstrap(ForvumHome home, Flyway flyway) {
        this.home = home;
        this.flyway = flyway;
    }

    void onStart(@Observes StartupEvent ev) {
        if (StateDirInitializer.ensureStateDir(home.state())) {
            flyway.migrate();
            LOG.debugf("Flyway migration applied; database at %s/forvum.sqlite", home.state());
        } else {
            LOG.warnf("State directory %s unavailable — skipping schema migration; "
                    + "persistence is disabled for this run", home.state());
        }
    }
}
