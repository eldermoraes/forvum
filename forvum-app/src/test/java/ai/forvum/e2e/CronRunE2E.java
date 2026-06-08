package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * E2E scenario 8 (ULTRAPLAN §7.4 / X6): a cron run (M19). With a {@code crons/tick.json} firing every
 * second present at boot, {@code CronScheduler} schedules it programmatically and the real Quarkus
 * {@code Scheduler} actually fires a full agent turn — through the in-process fake model — writing
 * {@code messages} (user + assistant), a {@code provider_calls} row, and a {@code capr_events} verdict
 * under the {@code cron:tick} session, with NO HTTP request and NO manual trigger.
 *
 * <p>This is the app-assembled counterpart of the engine's {@code CronSchedulerScheduleIT}: it proves the
 * scheduler boots and fires inside the FULL app (where {@code CommandMode} sees no one-shot argument in a
 * {@code @QuarkusTest}, so the cron startup observer runs) and that the {@code fake:} model resolves
 * through the app's {@code FakeModelProvider}. The DB rows are the observable side-effect (OTel spans do
 * not exist in v0.1, per X6's span-less guidance). The suite excludes inference via the fake model, per
 * the perf-gate convention.
 */
@QuarkusTest
@TestProfile(CronRunE2E.CronHomeProfile.class)
class CronRunE2E {

    private static final String SESSION = "cron:tick";

    private static long messageCount() {
        return QuarkusTransaction.requiringNew()
                .call(() -> MessageEntity.count("sessionId = ?1 and agentId = ?2", SESSION, "main"));
    }

    @Test
    void aCronFiresAFullLedgeredTurnViaTheRealScheduler() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && messageCount() < 2) {
            Thread.sleep(200);
        }

        assertTrue(messageCount() >= 2,
                "the scheduler fired a cron turn (user + assistant messages under cron:tick)");

        long providerCalls = QuarkusTransaction.requiringNew()
                .call(() -> ProviderCallEntity.count("sessionId = ?1", SESSION));
        long caprEvents = QuarkusTransaction.requiringNew()
                .call(() -> CaprEventEntity.count("sessionId = ?1", SESSION));
        assertTrue(providerCalls >= 1, "the cron turn was ledgered in provider_calls");
        assertTrue(caprEvents >= 1, "the cron turn wrote a capr_events verdict");
    }

    /** Seeds {@code main} (fake-backed) and a {@code tick} cron firing every second. */
    public static class CronHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-cron-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path crons = Files.createDirectories(home.resolve("crons"));
                // Quartz cron, every second, so the test does not wait a whole minute.
                Files.writeString(crons.resolve("tick.json"),
                        "{ \"cron\": \"0/1 * * * * ?\", \"agentId\": \"main\", "
                      + "\"primary\": \"fake:test-model\", \"prompt\": \"tick\" }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
