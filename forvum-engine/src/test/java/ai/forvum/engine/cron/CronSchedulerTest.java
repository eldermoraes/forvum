package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The hot-reload routing logic of {@link CronScheduler} (M19): a {@code ConfigurationChangedEvent} is
 * acted on only for {@code crons/<id>.json} paths, and the cron id is the file-name stem. Pure logic, no
 * Quarkus boot.
 */
class CronSchedulerTest {

    @Test
    void cronIdForExtractsTheStemOfACronJsonPath() {
        assertEquals(Optional.of("daily-brief"), CronScheduler.cronIdFor(Path.of("crons", "daily-brief.json")));
    }

    @Test
    void cronIdForIgnoresNonCronAndNonJsonPaths() {
        assertTrue(CronScheduler.cronIdFor(Path.of("agents", "main.json")).isEmpty(),
                "a non-crons subfolder is ignored");
        assertTrue(CronScheduler.cronIdFor(Path.of("crons", "notes.txt")).isEmpty(),
                "a non-.json file under crons is ignored");
        assertTrue(CronScheduler.cronIdFor(Path.of("config.json")).isEmpty(),
                "a root-level file is ignored");
    }
}
