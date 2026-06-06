package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.forvum.engine.agent.AgentRegistryTestHomeProfile;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.ForvumHome;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The M19 hot-reload contract ("new/changed/removed cron files reload WITHOUT restart"), driven
 * deterministically through {@link CronScheduler#onConfigChange} (the same {@code ConfigurationChangedEvent}
 * the M4 watcher fires) — independent of WatchService timing. Asserts via {@link Scheduler#getScheduledJob}:
 * a CREATED file schedules the job; an edit that makes it invalid UNSCHEDULES it (a stale job must not keep
 * firing); a re-validated edit reschedules it; a DELETED file removes it. The probe cron uses a far-future
 * expression so the scheduled job never actually fires during the test.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class CronSchedulerReloadIT {

    private static final String ID = "reload-probe";
    private static final String FILE = ID + ".json";
    private static final String VALID =
            "{ \"cron\": \"0 0 0 1 1 ?\", \"agentId\": \"faker\", \"primary\": \"fake:test-model\", \"prompt\": \"probe\" }";

    @Inject
    CronScheduler cronScheduler;

    @Inject
    Scheduler scheduler;

    @Inject
    ForvumHome home;

    private void write(String content) throws IOException {
        Path crons = Files.createDirectories(home.crons());
        Files.writeString(crons.resolve(FILE), content);
    }

    private void fire(ChangeType type) {
        cronScheduler.onConfigChange(new ConfigurationChangedEvent(Path.of("crons", FILE), type));
    }

    @Test
    void cronFilesRescheduleAndUnscheduleWithoutRestart() throws IOException {
        try {
            // CREATED → scheduled
            write(VALID);
            fire(ChangeType.CREATED);
            assertNotNull(scheduler.getScheduledJob(ID), "a new cron file schedules a job without restart");

            // MODIFIED to invalid → the stale job is unscheduled (not left firing the old spec)
            write("{ not valid json");
            fire(ChangeType.MODIFIED);
            assertNull(scheduler.getScheduledJob(ID), "an edit that makes the cron invalid stops the job");

            // MODIFIED back to valid → rescheduled
            write(VALID);
            fire(ChangeType.MODIFIED);
            assertNotNull(scheduler.getScheduledJob(ID), "a re-validated cron is rescheduled");

            // DELETED → unscheduled
            fire(ChangeType.DELETED);
            assertNull(scheduler.getScheduledJob(ID), "removing the cron file unschedules the job");
        } finally {
            scheduler.unscheduleJob(ID); // hygiene: never leave a probe job in the shared scheduler
            Files.deleteIfExists(home.crons().resolve(FILE));
        }
    }
}
