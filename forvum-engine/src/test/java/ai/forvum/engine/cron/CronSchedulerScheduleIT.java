package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * The M19 Verify (real scheduler): with a {@code crons/tick.json} firing every second present at boot,
 * {@link CronScheduler} schedules it programmatically and the Quarkus {@link io.quarkus.scheduler.Scheduler}
 * actually fires a turn — writing {@code messages}/{@code provider_calls}/{@code capr_events} under the
 * {@code cron:tick} session — without any HTTP request or manual trigger.
 */
@QuarkusTest
@TestProfile(CronTestHomeProfile.class)
class CronSchedulerScheduleIT {

    private static final String SESSION = "cron:tick";

    private static long messageCount() {
        return QuarkusTransaction.requiringNew()
                .call(() -> MessageEntity.count("sessionId = ?1 and agentId = ?2", SESSION, "faker"));
    }

    @Test
    void everySecondCronFiresATurnViaTheRealScheduler() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && messageCount() < 2) {
            Thread.sleep(200);
        }

        assertTrue(messageCount() >= 2, "the scheduler fired a cron turn (user + assistant messages)");
        long providerCalls = QuarkusTransaction.requiringNew()
                .call(() -> ProviderCallEntity.count("sessionId = ?1", SESSION));
        long caprEvents = QuarkusTransaction.requiringNew()
                .call(() -> CaprEventEntity.count("sessionId = ?1", SESSION));
        assertTrue(providerCalls >= 1, "the cron turn was ledgered in provider_calls");
        assertTrue(caprEvents >= 1, "the cron turn wrote a capr_events verdict");
    }
}
