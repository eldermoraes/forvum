package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * #167 agent-level role cap applied to a CRON turn ({@link CronScheduler#fire}). The cron already binds the
 * distinguished read-only {@code cron} role (FS_READ); the agent's declared cap (roles=["writer"] &rarr;
 * FS_WRITE only) intersects it to the EMPTY set, so even an {@code fs.read} the cron role alone would permit
 * is denied + audited. The scripted model emits {@code fs.read}; WITHOUT the agent cap the cron role would
 * run it — that unattended-execution gap is exactly what this closes (acceptance: "interactive, cron, ...
 * flows calculate the same effective scope set"). Calls {@code fire} directly (deterministic), like
 * {@link CronSchedulerFireIT}. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(CronSchedulerRoleCapIT.CronRoleCapHomeProfile.class)
class CronSchedulerRoleCapIT {

    @Inject
    CronScheduler cronScheduler;

    @Test
    void theAgentRoleCapRestrictsACronTurnBeyondTheReadOnlyCronRole() {
        CronSpec spec = new CronSpec("rolecap", "0 * * * * ?", new AgentId("capped-cron"),
                ModelRef.parse("scripted-read:m"), "read the day's notes", Delivery.NONE);

        cronScheduler.fire(spec);

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "cron:rolecap", "denied", "fs.read"),
                "the agent's writer cap intersects the cron role to empty — even fs.read is denied");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "cron:rolecap", "ok", "fs.read"),
                "the capped cron call must never run");
    }

    /** Seeds {@code capped-cron} (belt fs.read, roles=["writer"] =&gt; FS_WRITE only) + a {@code writer} role. */
    public static class CronRoleCapHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-cron-rolecap-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("capped-cron.md"), "You are a capped cron agent.");
                Files.writeString(agents.resolve("capped-cron.json"),
                        "{ \"primaryModel\": \"scripted-read:m\", \"allowedTools\": [\"fs.read\"], "
                      + "\"roles\": [\"writer\"] }");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("writer.json"), "{ \"scopes\": [\"FS_WRITE\"] }");
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
