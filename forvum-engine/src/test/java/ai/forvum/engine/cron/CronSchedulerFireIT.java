package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistryTestHomeProfile;
import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

/**
 * The cron-fire path (M19): {@link CronScheduler#fire} runs a full M18 turn for the cron's agent using
 * the cron's OWN model and leaves it fully ledgered — two {@code messages} (user + assistant), one
 * {@code provider_calls} row, and one {@code capr_events} verdict, all under the {@code cron:<id>}
 * session. Calls {@code fire} directly (deterministic) against the {@code faker} agent + the in-process
 * fake model; the real-scheduler timing is covered by {@code CronSchedulerScheduleIT}.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class CronSchedulerFireIT {

    @Inject
    CronScheduler cronScheduler;

    @Test
    void fireRunsACronTurnWithTheCronsOwnModelAndLedgersIt() {
        // The cron's model differs from the faker agent's persona model (fake:test-model): asserting the
        // ledger records the CRON's model proves the override path (LlmSelector.resolve + Agent.respond
        // override) actually used it, not the persona model — the headline M19 decision.
        CronSpec spec = new CronSpec("brief", "0 * * * * ?", new AgentId("faker"),
                ModelRef.parse("fake:cron-only-model"), "summarize the day");

        cronScheduler.fire(spec);

        String session = "cron:brief";
        assertEquals(2, MessageEntity.count("sessionId = ?1 and agentId = ?2", session, "faker"),
                "cron turn persisted user + assistant messages");
        assertEquals(1, ProviderCallEntity.count("sessionId = ?1 and agentId = ?2", session, "faker"),
                "one provider_calls ledger row for the cron turn");
        assertEquals(1, CaprEventEntity.count("sessionId = ?1 and agentId = ?2", session, "faker"),
                "one capr_events verdict for the cron turn");

        ProviderCallEntity call = ProviderCallEntity.find("sessionId = ?1 and agentId = ?2", session, "faker")
                .firstResult();
        assertEquals("cron-only-model", call.model,
                "the cron turn used the cron's OWN model, not the agent's persona model (fake:test-model)");
    }
}
