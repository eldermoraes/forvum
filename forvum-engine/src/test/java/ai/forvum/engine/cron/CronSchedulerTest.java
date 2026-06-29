package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.core.PermissionScope;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.agent.RoleRegistry;
import ai.forvum.engine.routing.LlmSelector;
import ai.forvum.engine.runtime.CommandMode;

import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The hot-reload routing logic of {@link CronScheduler} (M19) and its M20 one-shot cold-start guard: a
 * {@code ConfigurationChangedEvent} is acted on only for {@code crons/<id>.json} paths, and a one-shot
 * command schedules nothing. Also drives {@link CronScheduler#fire} end-to-end against stubbed
 * collaborators (P2-CRON-DELIVERY) to prove a successful turn's reply reaches the sink exactly once and a
 * failed turn skips delivery. Pure logic, no Quarkus boot (mirrors {@link CronDeliveryRoutingTest}).
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

    // ---- P2-CRON-DELIVERY: fire() end-to-end (stubbed collaborators) -------------------------------

    @Test
    void fireDeliversTheActualTurnReplyOnceForModeLast() {
        RecordingSink sink = new RecordingSink();
        CronScheduler cron = fireScheduler(sink, new ScriptedAgent("the cron reply", false));
        CronSpec spec = new CronSpec("brief", "0 * * * * ?", new AgentId("faker"),
                ModelRef.parse("fake:m"), "summarize the day", new Delivery(DeliveryMode.LAST, null));

        cron.fire(spec);

        assertEquals(1, sink.delivered.size(), "a successful fire delivers exactly once");
        CronDelivery d = sink.delivered.getFirst();
        assertEquals("brief", d.cronId());
        assertEquals("faker", d.agentId());
        assertEquals("the cron reply", d.reply(), "the sink carries the ACTUAL turn reply");
        assertEquals(DeliveryMode.LAST, d.delivery().mode());
    }

    @Test
    void fireSkipsDeliveryWhenTheTurnThrows() {
        RecordingSink sink = new RecordingSink();
        CronScheduler cron = fireScheduler(sink, new ScriptedAgent("unused", true));
        CronSpec spec = new CronSpec("brief", "0 * * * * ?", new AgentId("faker"),
                ModelRef.parse("fake:m"), "summarize the day", new Delivery(DeliveryMode.LAST, null));

        assertDoesNotThrow(() -> cron.fire(spec), "a failed turn is logged, never fatal");

        assertTrue(sink.delivered.isEmpty(), "a failed turn has no reply to deliver — the sink is never invoked");
    }

    /** A scheduler whose {@code fire()} collaborators are all stubbed: registry/llmSelector/roleRegistry + sink. */
    private static CronScheduler fireScheduler(RecordingSink sink, ScriptedAgent agent) {
        CronScheduler cron = new CronScheduler();
        cron.deliverySink = sink;
        cron.registry = new StubRegistry(agent);
        cron.llmSelector = new StubLlmSelector();
        cron.roleRegistry = new StubRoleRegistry();
        return cron;
    }

    /** A recording sink that captures every delivered payload (mirrors {@link CronDeliveryRoutingTest}). */
    static final class RecordingSink implements CronDeliverySink {
        final List<CronDelivery> delivered = new ArrayList<>();

        @Override
        public void deliver(CronDelivery delivery) {
            delivered.add(delivery);
        }
    }

    /**
     * An {@link Agent} stub whose {@code respond} returns a fixed reply or throws — the scripted turn.
     * {@code @Vetoed} keeps it (and the registry/selector/role stubs below) out of ArC bean discovery: a
     * CDI scope ({@code @AgentScoped}/{@code @ApplicationScoped}) is {@code @Inherited}, so an un-vetoed
     * subclass would become a second ambiguous bean and break the engine's {@code @QuarkusTest} boot.
     */
    @Vetoed
    static final class ScriptedAgent extends Agent {
        private final String reply;
        private final boolean boom;

        ScriptedAgent(String reply, boolean boom) {
            this.reply = reply;
            this.boom = boom;
        }

        @Override
        public String respond(String sessionId, String userText, ChatModel modelOverride) {
            if (boom) {
                throw new RuntimeException("turn boom");
            }
            return reply;
        }
    }

    @Vetoed
    static final class StubRegistry extends AgentRegistry {
        private final Agent agent;

        StubRegistry(Agent agent) {
            this.agent = agent;
        }

        @Override
        public Agent getOrCreate(AgentId id) {
            return agent;
        }

        // #167: fire() reads the agent's role cap via persona(id). The stub agent declares no roles (no cap),
        // so capScopes leaves the cron role's scopes unchanged and the delivery path is unaffected.
        @Override
        public Persona persona(AgentId id) {
            return new Persona(id, "stub persona", List.of(), ModelRef.parse("fake:m"), null, null, null, null);
        }
    }

    @Vetoed
    static final class StubLlmSelector extends LlmSelector {
        @Override
        public ChatModel resolve(ModelRef ref, String agentId, String sessionId) {
            return null; // the scripted agent ignores the model override
        }
    }

    @Vetoed
    static final class StubRoleRegistry extends RoleRegistry {
        @Override
        public Set<PermissionScope> scopesFor(String roleName) {
            return Set.of(PermissionScope.FS_READ);
        }
    }
}
