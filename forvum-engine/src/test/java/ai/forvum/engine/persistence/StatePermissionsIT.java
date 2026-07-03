package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Owner-only permission enforcement over a REAL Flyway/Hibernate boot (#173, DR-6c [6c-DP-13]). Boots Quarkus
 * against a temp {@code $FORVUM_HOME} whose {@code state/} is NOT pre-created ({@link PersistenceTestHomeProfile}),
 * so {@link PersistenceBootstrap} creates + hardens the directory, migrates the schema (creating
 * {@code forvum.sqlite}), and hardens the database file. Asserts the durable {@code 0700} directory and the
 * {@code 0600} database after real DB activity — the wal/shm sidecars are covered by containment under the
 * {@code 0700} directory (and their individual mode is unit-tested in {@link StateDirInitializerTest}).
 *
 * <p>Runs via Surefire — {@code forvum-engine} is a headless library, so a {@code @QuarkusTest} boots in-JVM
 * (CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class StatePermissionsIT {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Inject
    EntityManager em;

    @Test
    void stateDirectoryAndDatabaseAreOwnerOnlyAfterARealBoot() throws IOException {
        assumeTrue(POSIX, "POSIX-only owner-only assertion");
        // Real Hibernate/Agroal activity against the migrated SQLite store (the DB file already exists from
        // PersistenceBootstrap's boot-time migration + hardening pass).
        assertEquals(1, ((Number) em.createNativeQuery("select 1").getSingleResult()).intValue());

        Path state = PersistenceTestHomeProfile.HOME.resolve("state");
        Path database = state.resolve("forvum.sqlite");

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state),
                "state/ must be owner-only (0700) after a real boot, independent of the process umask");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(database),
                "the SQLite database must be owner-only (0600) after real Flyway/Hibernate activity");
    }
}
