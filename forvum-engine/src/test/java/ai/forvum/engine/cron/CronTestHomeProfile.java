package ai.forvum.engine.cron;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with a {@code faker} agent (pinned to
 * the in-process fake model) and a {@code tick} cron firing every second, so {@link CronScheduler} can be
 * observed actually scheduling + firing a turn through the real Quarkus {@link io.quarkus.scheduler.Scheduler}.
 */
public class CronTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = seed();

    private static Path seed() {
        try {
            Path home = Files.createTempDirectory("forvum-cron-home");
            Path agents = Files.createDirectories(home.resolve("agents"));
            Files.writeString(agents.resolve("faker.md"), "You are a scheduled test agent.");
            Files.writeString(agents.resolve("faker.json"),
                    "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
            Path crons = Files.createDirectories(home.resolve("crons"));
            // Quartz cron, every second, so the IT does not wait a whole minute.
            Files.writeString(crons.resolve("tick.json"),
                    "{ \"cron\": \"0/1 * * * * ?\", \"agentId\": \"faker\", "
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
