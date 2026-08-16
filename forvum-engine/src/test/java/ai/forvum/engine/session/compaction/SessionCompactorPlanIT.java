package ai.forvum.engine.session.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pins the #190 plan-block compaction rules on top of the P2-COMPACT invariants: among {@code PLAN}
 * rows older than the retain boundary the session's NEWEST plan row (the live plan) is retained
 * regardless of age while every superseded (older) plan row is stripped like an orphan, and plan
 * content never leaks into the summary. Fixture mirrors {@link SessionCompactorIT} (one predictable
 * drop boundary):
 *
 * <pre>
 *   id  role       block          tokens createdAt  fate
 *   p1  user       turn_message    100   2000       dropped -> reclaimed by the summary
 *   p2  tool       plan             20   2001       SUPERSEDED plan -> stripped as an orphan
 *   p3  assistant  turn_message    100   2002       dropped (folded into the summary)
 *   p4  tool       plan             20   2003       LIVE plan (newest) -> RETAINED despite its age
 *   p5  user       turn_message    100   3000       RETAINED (boundary)
 *   p6  assistant  turn_message    100   3002       RETAINED
 * </pre>
 *
 * Region = 440 tokens > floor 300; retain budget 250 keeps p6(100)+p5(100), then p3 would push to
 * 300 -> break, so the boundary is p5's createdAt (3000). Drops p1,p3 (summarized) and strips p2
 * (superseded plan); p4 survives verbatim. Each test uses its own session (shared-DB rule).
 */
@QuarkusTest
@TestProfile(CompactionTestHomeProfile.class)
class SessionCompactorPlanIT {

    private static final String SESSION = "compact-plan-1";
    private static final String AGENT = "plan-compactor-agent";

    @Inject
    SessionCompactor compactor;

    @Test
    void retainsTheLivePlanAndStripsSupersededPlans() {
        Seed seed = QuarkusTransaction.requiringNew().call(this::seed);

        CompactionResult result = compactor.compact(SESSION, AGENT, new CompactionPolicy(300, 250));

        assertTrue(result.compacted(), "the 440-token region exceeds the 300 floor -> a pass runs");
        assertEquals(2, result.summarizedTurns(), "the two old turn messages (p1, p3) are folded");
        assertEquals(1, result.orphansStripped(), "ONLY the superseded plan (p2) is stripped");

        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> survivors = MessageEntity.list(
                    "sessionId = ?1 and agentId = ?2 order by id", SESSION, AGENT);

            assertNull(byId(survivors, seed.p2), "the SUPERSEDED plan row is stripped");
            MessageEntity live = byId(survivors, seed.p4);
            assertNotNull(live, "the LIVE (newest) plan row survives even though it is older than the boundary");
            assertEquals("[x] step one\n[>] step two", live.content, "the live plan survives verbatim");
            assertEquals(Role.TOOL.dbValue(), live.role);
            assertEquals(BlockType.PLAN.dbValue(), live.blockType);

            assertNotNull(byId(survivors, seed.p5), "the retained turn survives");
            assertNotNull(byId(survivors, seed.p6), "the retained turn survives");

            // The summary reclaims p1's id and carries NO plan content (plans are never summarized).
            MessageEntity summary = byId(survivors, seed.p1);
            assertNotNull(summary, "a summary message reclaims p1's id");
            assertEquals(Role.SYSTEM.dbValue(), summary.role);
            assertFalse(summary.content.contains("step one"),
                    "plan content never enters the summary row: " + summary.content);

            // The retained live plan's id (p4) sits ABOVE the reclaimed summary id (p1), so it stays in
            // the compactable region; either way it is never deleted and injection reads newest-by-session.
            SessionEntity session = SessionEntity.findById(SESSION);
            assertEquals(seed.p1.intValue(), session.cachedPrefixEndIndex,
                    "the frozen prefix ends at the reclaimed summary id");
        });
    }

    private static MessageEntity byId(List<MessageEntity> rows, Long id) {
        return rows.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
    }

    private Seed seed() {
        SessionEntity session = new SessionEntity();
        session.id = SESSION;
        session.identityId = "id";
        session.channelId = "test";
        session.agentId = AGENT;
        session.startedAt = 1000;
        session.lastSeenAt = 4000;
        session.persist();

        Long p1 = msg(Role.USER, BlockType.TURN_MESSAGE, "old q1", 100, 2000);
        Long p2 = msg(Role.TOOL, BlockType.PLAN, "[>] step one", 20, 2001);
        Long p3 = msg(Role.ASSISTANT, BlockType.TURN_MESSAGE, "old a1", 100, 2002);
        Long p4 = msg(Role.TOOL, BlockType.PLAN, "[x] step one\n[>] step two", 20, 2003);
        Long p5 = msg(Role.USER, BlockType.TURN_MESSAGE, "recent q2", 100, 3000);
        Long p6 = msg(Role.ASSISTANT, BlockType.TURN_MESSAGE, "recent a2", 100, 3002);

        return new Seed(p1, p2, p3, p4, p5, p6);
    }

    private Long msg(Role role, BlockType block, String content, int tokens, long createdAt) {
        MessageEntity m = new MessageEntity();
        m.sessionId = SESSION;
        m.agentId = AGENT;
        m.role = role.dbValue();
        m.content = content;
        m.tokens = tokens;
        m.blockType = block.dbValue();
        m.createdAt = createdAt;
        m.persist();
        return m.id;
    }

    private record Seed(Long p1, Long p2, Long p3, Long p4, Long p5, Long p6) {
    }
}
