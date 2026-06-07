package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.runtime.CommandMode;

import io.quarkus.runtime.StartupEvent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The hot-reload routing logic of {@link CronScheduler} (M19) and its M20 one-shot cold-start guard: a
 * {@code ConfigurationChangedEvent} is acted on only for {@code crons/<id>.json} paths, and a one-shot
 * command schedules nothing. Pure logic, no Quarkus boot.
 */
class CronSchedulerTest {

    @Test
    void oneShotCommandSchedulesNoCrons() {
        CronScheduler cron = new CronScheduler();
        cron.commandMode = new CommandMode(new String[] {"init"});
        // scheduler + cronReader are intentionally left null: the one-shot guard must return BEFORE
        // reading them, so a regression that drops the guard NPEs here instead of silently scheduling
        // crons (which can fire a turn against the un-migrated DB — review finding #8).
        assertDoesNotThrow(() -> cron.onStart(new StartupEvent()),
                "a one-shot command must not touch the scheduler/cron reader");
    }

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
