package ai.forvum.engine.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentMemory;
import ai.forvum.engine.agent.AgentRegistryTestHomeProfile;
import ai.forvum.engine.agent.SessionManager;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.MessageEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pins the {@link PanachePlanStore} row contract against real SQLite (#190): append-only (a save never
 * updates/deletes — a superseded row may sit in the frozen compaction prefix), newest-wins {@code latest},
 * the exact {@code role='tool'} / {@code block_type='plan'} literals, per-(session, agent) isolation, and
 * the regression pin that plan rows never re-enter the {@link AgentMemory} history rebuild (the framed
 * injection is the plan's ONLY window surface). Assertions are scoped to this test's own sessions
 * (shared-{@code @TestProfile} DB, CLAUDE.md section 14).
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class PlanStoreIT {

    @Inject
    PanachePlanStore store;

    @Inject
    SessionManager sessions;

    @Inject
    AgentMemory memory;

    @Test
    void savesAreAppendOnlyAndLatestReturnsTheNewest() {
        AgentId agent = new AgentId("plan-agent");
        String session = "plan-sess-append";
        QuarkusTransaction.requiringNew().run(() -> sessions.ensureSession(session, agent));

        store.save(session, agent.value(), "[>] step one");
        store.save(session, agent.value(), "[x] step one\n[>] step two");

        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> rows = MessageEntity.list(
                    "sessionId = ?1 and agentId = ?2 order by id", session, agent.value());
            assertEquals(2, rows.size(), "two saves -> two rows, append-only (never update in place)");
            for (MessageEntity row : rows) {
                assertEquals(Role.TOOL.dbValue(), row.role, "a plan row is role='tool'");
                assertEquals(BlockType.PLAN.dbValue(), row.blockType, "a plan row is block_type='plan'");
            }
        });
        assertEquals("[x] step one\n[>] step two", store.latest(session, agent.value()).orElseThrow(),
                "latest returns the newest (live) plan");
    }

    @Test
    void plansAreIsolatedPerSessionAndAgent() {
        AgentId agentA = new AgentId("plan-agent-a");
        AgentId agentB = new AgentId("plan-agent-b");
        QuarkusTransaction.requiringNew().run(() -> {
            sessions.ensureSession("plan-iso-1", agentA);
            sessions.ensureSession("plan-iso-1", agentB);
            sessions.ensureSession("plan-iso-2", agentA);
        });

        store.save("plan-iso-1", agentA.value(), "plan a1");
        store.save("plan-iso-1", agentB.value(), "plan b1");

        assertEquals("plan a1", store.latest("plan-iso-1", agentA.value()).orElseThrow());
        assertEquals("plan b1", store.latest("plan-iso-1", agentB.value()).orElseThrow());
        assertTrue(store.latest("plan-iso-2", agentA.value()).isEmpty(),
                "another session of the same agent holds no plan");
    }

    @Test
    void planRowsNeverSurfaceInTheConversationalHistoryRebuild() throws Exception {
        AgentId agent = new AgentId("plan-agent-history");
        String session = "plan-sess-history";
        QuarkusTransaction.requiringNew().run(() -> sessions.ensureSession(session, agent));

        ScopedValue.where(CurrentAgent.CURRENT_AGENT, agent).run(() -> {
            memory.addUserMessage(session, "question");
            memory.addAssistantMessage(session, "answer");
        });
        store.save(session, agent.value(), "[>] the plan");

        var history = ScopedValue.where(CurrentAgent.CURRENT_AGENT, agent)
                .call(() -> QuarkusTransaction.requiringNew().call(() -> memory.messages(session)));
        assertEquals(2, history.size(),
                "role='tool' keeps the plan row out of the rebuilt conversation (no double-feed)");
    }
}
