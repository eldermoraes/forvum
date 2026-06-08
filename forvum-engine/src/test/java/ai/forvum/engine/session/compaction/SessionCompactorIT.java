package ai.forvum.engine.session.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pins the P2-COMPACT prefix-preserving session-compaction invariants against real SQLite, driven by the
 * deterministic {@link StubSummarizer} (no live model). The fixture is engineered so the retain walk has
 * exactly one drop boundary, so every counter and surviving/stripped row is exactly predictable:
 *
 * <pre>
 *   id  role       block            tokens createdAt  fate
 *   m1  system     turn_message      10    1000       PREFIX (frozen; cachedPrefixEndIndex = m1)
 *   m2  user       turn_message     100    2000       dropped -> reclaimed by the summary
 *   m3  assistant  turn_reasoning    50    2001       orphan stripped (older than boundary)
 *   m4  assistant  turn_message     100    2002       dropped; its capr verdict archived
 *   m5  tool       tool_execution    30    2003       stale tool stripped (older than boundary)
 *   m6  user       turn_message     100    3000       RETAINED (oldest retained user = boundary)
 *   m7  tool       tool_execution    30    3001       RETAINED (connected to a retained turn)
 *   m8  assistant  turn_message     100    3002       RETAINED; its capr verdict NOT archived
 * </pre>
 *
 * Region (m2..m8) = 510 tokens > floor 300; retain budget 250 keeps m8(100)+m6(100), then m4 would push
 * to 300 -> break, so the boundary is m6's createdAt (3000). Drops m2,m4 (turn messages -> summarized)
 * and strips m3,m5 (orphans). Each test uses its own session (shared-DB pollution rule, CLAUDE.md s14).
 */
@QuarkusTest
@TestProfile(CompactionTestHomeProfile.class)
class SessionCompactorIT {

    private static final String SESSION = "compact-1";
    private static final String TINY_SESSION = "compact-tiny-1";
    private static final String BIG_ASSISTANT_SESSION = "compact-big-assistant-1";
    private static final String AGENT = "compactor-agent";

    @Inject
    SessionCompactor compactor;

    @Test
    void compactsOldestFirstPreservesPrefixStripsOrphansAndArchivesCapr() {
        Seed seed = QuarkusTransaction.requiringNew().call(this::seed);

        CompactionResult result = compactor.compact(SESSION, AGENT, new CompactionPolicy(300, 250));

        // --- result counters ---
        assertTrue(result.compacted(), "the 510-token region exceeds the 300 floor -> a pass runs");
        assertEquals(2, result.summarizedTurns(), "exactly the two oldest turn messages (m2, m4) are folded");
        assertEquals(2, result.orphansStripped(), "the orphaned reasoning (m3) + stale tool (m5) are stripped");
        assertEquals(1, result.caprArchived(), "only m4's verdict is archived (m8 stays live)");

        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> survivors = MessageEntity.list(
                    "sessionId = ?1 and agentId = ?2 order by id", SESSION, AGENT);

            // --- prefix preservation: m1 is byte-identical and still present ---
            MessageEntity prefix = byId(survivors, seed.m1);
            assertNotNull(prefix, "the frozen prefix message (m1) must survive untouched");
            assertEquals("sys prompt", prefix.content, "the prefix content is never mutated");

            // --- oldest-first: m3/m4/m5 dropped, retained m6/m7/m8 remain ---
            assertNull(byId(survivors, seed.m3), "orphaned reasoning block stripped");
            assertNull(byId(survivors, seed.m4), "oldest assistant turn dropped (folded into the summary)");
            assertNull(byId(survivors, seed.m5), "stale tool-execution block (older than boundary) stripped");
            assertNotNull(byId(survivors, seed.m6), "the oldest retained user message survives");
            assertNotNull(byId(survivors, seed.m7), "a tool-execution block connected to a retained turn survives");
            assertNotNull(byId(survivors, seed.m8), "the most-recent assistant turn survives");

            // --- the summary reclaimed the OLDEST dropped id (m2) and joined the frozen prefix ---
            MessageEntity summary = byId(survivors, seed.m2);
            assertNotNull(summary, "a summary message reclaims m2's id");
            assertEquals(Role.SYSTEM.dbValue(), summary.role, "the summary is a system block");
            assertEquals(BlockType.TURN_MESSAGE.dbValue(), summary.blockType, "the summary is a plain turn message");
            assertTrue(summary.content.contains(StubSummarizer.PREFIX),
                    "the summary content came from the injected stub summarizer (no live model)");
            assertTrue(summary.content.contains("old q1") && summary.content.contains("old a1"),
                    "the summary folds the dropped turns' content, oldest first");

            // --- cachedPrefixEndIndex advanced to the summary; the prefix grows monotonically ---
            SessionEntity session = SessionEntity.findById(SESSION);
            assertEquals(seed.m2.intValue(), session.cachedPrefixEndIndex,
                    "the frozen prefix now ends at the reclaimed summary id");

            // --- CAPR: m4's verdict archived (not deleted), m8's still live, none deleted ---
            assertEquals(1, CaprEventEntity.count(
                    "sessionId = ?1 and agentId = ?2 and turnId = ?3 and archived = true", SESSION, AGENT, seed.m4),
                    "m4's verdict is archived, not deleted");
            assertEquals(1, CaprEventEntity.count(
                    "sessionId = ?1 and agentId = ?2 and turnId = ?3 and archived = false", SESSION, AGENT, seed.m8),
                    "m8's verdict stays live");
            assertEquals(2, CaprEventEntity.count("sessionId = ?1 and agentId = ?2", SESSION, AGENT),
                    "no capr row is ever deleted (append-only)");
        });
    }

    @Test
    void leavesAnUnderFloorSessionUntouched() {
        Long onlyMsg = QuarkusTransaction.requiringNew().call(this::seedTiny);

        CompactionResult result = compactor.compact(TINY_SESSION, AGENT, new CompactionPolicy(300, 250));

        assertFalse(result.compacted(), "a session under the reserve floor is a no-op");
        assertEquals(0, result.summarizedTurns());
        assertEquals(0, result.orphansStripped());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(MessageEntity.findById(onlyMsg), "the lone message is untouched");
            SessionEntity session = SessionEntity.findById(TINY_SESSION);
            assertNull(session.cachedPrefixEndIndex, "no prefix is recorded when nothing is compacted");
        });
    }

    /**
     * Regression for the retain-boundary bug: when the region's NEWEST {@code TURN_MESSAGE} is an
     * ASSISTANT reply that alone exceeds {@code retainTokens} (the typical case — compaction runs before
     * the next user message is persisted), the "keep >= 1 most-recent turn verbatim" guarantee must still
     * hold. The old boundary tracked only USER rows, so an assistant-only newest turn left the boundary at
     * {@code Long.MAX_VALUE} and the whole region — including that newest turn — was summarized away.
     *
     * <pre>
     *   id  role       block          tokens createdAt  fate
     *   b1  system     turn_message     10   1000       PREFIX (frozen)
     *   b2  user       turn_message    100   2000       dropped -> reclaimed by the summary
     *   b3  assistant  turn_message    100   2001       dropped; its capr verdict archived
     *   b4  assistant  turn_message    300   3000       RETAINED VERBATIM (newest turn; alone > retain 250)
     * </pre>
     *
     * Region (b2..b4) = 500 tokens > floor 300; retain budget 250 keeps only b4 (300, the lone most-recent
     * turn), so the boundary is b4's createdAt (3000) and b4 survives unchanged.
     */
    @Test
    void keepsNewestAssistantTurnVerbatimWhenItAloneExceedsRetain() {
        BigSeed seed = QuarkusTransaction.requiringNew().call(this::seedBigAssistant);

        CompactionResult result = compactor.compact(
                BIG_ASSISTANT_SESSION, AGENT, new CompactionPolicy(300, 250));

        assertTrue(result.compacted(), "the 500-token region exceeds the 300 floor -> a pass runs");
        assertEquals(2, result.summarizedTurns(), "the two oldest turn messages (b2, b3) are folded");
        assertEquals(1, result.caprArchived(), "b3's verdict is archived");

        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> survivors = MessageEntity.list(
                    "sessionId = ?1 and agentId = ?2 order by id", BIG_ASSISTANT_SESSION, AGENT);

            // The newest assistant turn survives VERBATIM (not summarized, content untouched).
            MessageEntity newest = byId(survivors, seed.b4);
            assertNotNull(newest, "the newest assistant turn must survive even though it alone exceeds retain");
            assertEquals("big recent reply", newest.content, "the newest turn is kept verbatim, never summarized");
            assertEquals(Role.ASSISTANT.dbValue(), newest.role, "the newest turn keeps its assistant role");

            // b3 (older assistant) dropped; b2's id reclaimed by the system summary.
            assertNull(byId(survivors, seed.b3), "the older assistant turn is folded into the summary");
            MessageEntity summary = byId(survivors, seed.b2);
            assertNotNull(summary, "a summary message reclaims b2's id");
            assertEquals(Role.SYSTEM.dbValue(), summary.role, "the summary is a system block");
            assertTrue(summary.content.contains(StubSummarizer.PREFIX),
                    "the summary content came from the injected stub summarizer (no live model)");

            SessionEntity session = SessionEntity.findById(BIG_ASSISTANT_SESSION);
            assertEquals(seed.b2.intValue(), session.cachedPrefixEndIndex,
                    "the frozen prefix ends at the reclaimed summary id, NOT past the newest turn");
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

        Long m1 = msg(SESSION, Role.SYSTEM, BlockType.TURN_MESSAGE, "sys prompt", 10, 1000);
        Long m2 = msg(SESSION, Role.USER, BlockType.TURN_MESSAGE, "old q1", 100, 2000);
        Long m3 = msg(SESSION, Role.ASSISTANT, BlockType.TURN_REASONING, "reasoning for q1", 50, 2001);
        Long m4 = msg(SESSION, Role.ASSISTANT, BlockType.TURN_MESSAGE, "old a1", 100, 2002);
        Long m5 = msg(SESSION, Role.TOOL, BlockType.TOOL_EXECUTION, "tool for q1", 30, 2003);
        Long m6 = msg(SESSION, Role.USER, BlockType.TURN_MESSAGE, "recent q2", 100, 3000);
        Long m7 = msg(SESSION, Role.TOOL, BlockType.TOOL_EXECUTION, "tool for q2", 30, 3001);
        Long m8 = msg(SESSION, Role.ASSISTANT, BlockType.TURN_MESSAGE, "recent a2", 100, 3002);

        // Freeze m1 as the existing cached prefix (simulating a prior compaction).
        session.cachedPrefixEndIndex = m1.intValue();

        capr(SESSION, m4); // verdict for the dropped turn -> must be archived
        capr(SESSION, m8); // verdict for a retained turn -> must stay live

        return new Seed(m1, m2, m3, m4, m5, m6, m7, m8);
    }

    private Long seedTiny() {
        SessionEntity session = new SessionEntity();
        session.id = TINY_SESSION;
        session.identityId = "id";
        session.channelId = "test";
        session.agentId = AGENT;
        session.startedAt = 1000;
        session.lastSeenAt = 1000;
        session.persist();
        return msg(TINY_SESSION, Role.USER, BlockType.TURN_MESSAGE, "hi", 5, 1000);
    }

    private BigSeed seedBigAssistant() {
        SessionEntity session = new SessionEntity();
        session.id = BIG_ASSISTANT_SESSION;
        session.identityId = "id";
        session.channelId = "test";
        session.agentId = AGENT;
        session.startedAt = 1000;
        session.lastSeenAt = 4000;
        session.persist();

        Long b1 = msg(BIG_ASSISTANT_SESSION, Role.SYSTEM, BlockType.TURN_MESSAGE, "sys prompt", 10, 1000);
        Long b2 = msg(BIG_ASSISTANT_SESSION, Role.USER, BlockType.TURN_MESSAGE, "old q", 100, 2000);
        Long b3 = msg(BIG_ASSISTANT_SESSION, Role.ASSISTANT, BlockType.TURN_MESSAGE, "old a", 100, 2001);
        Long b4 = msg(BIG_ASSISTANT_SESSION, Role.ASSISTANT, BlockType.TURN_MESSAGE, "big recent reply", 300, 3000);

        session.cachedPrefixEndIndex = b1.intValue();

        capr(BIG_ASSISTANT_SESSION, b3); // verdict for the dropped assistant turn -> must be archived

        return new BigSeed(b1, b2, b3, b4);
    }

    private Long msg(String sessionId, Role role, BlockType block, String content, int tokens, long createdAt) {
        MessageEntity m = new MessageEntity();
        m.sessionId = sessionId;
        m.agentId = AGENT;
        m.role = role.dbValue();
        m.content = content;
        m.tokens = tokens;
        m.blockType = block.dbValue();
        m.createdAt = createdAt;
        m.persist();
        return m.id;
    }

    private void capr(String sessionId, Long turnId) {
        CaprEventEntity c = new CaprEventEntity();
        c.sessionId = sessionId;
        c.agentId = AGENT;
        c.turnId = turnId;
        c.passed = 1;
        c.judgeModel = "none";
        c.rationale = "test";
        c.archived = false;
        c.createdAt = turnId;
        c.persist();
    }

    private record Seed(Long m1, Long m2, Long m3, Long m4, Long m5, Long m6, Long m7, Long m8) {
    }

    private record BigSeed(Long b1, Long b2, Long b3, Long b4) {
    }
}
