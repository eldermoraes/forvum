package ai.forvum.engine.session.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.MessageEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit contract for {@link SessionCompactor}'s pure retain-budget walk (#180): {@code retainBoundary}
 * decides which turns survive a compaction pass and {@code estimateTokens} supplies its budget inputs.
 * These pin the documented invariants — newest-first accumulation, the keep-at-least-one guarantee
 * (the newest TURN_MESSAGE survives even when it alone blows the budget), non-message rows never
 * driving the budget, and the measured-tokens/chars-per-4 estimate — without a Quarkus boot (X3:
 * coverage is the Surefire run). The transactional plan/apply passes stay pinned by SessionCompactorIT.
 */
class SessionCompactorBoundaryTest {

    @Test
    void estimateUsesMeasuredTokensWhenPresent() {
        assertEquals(37, SessionCompactor.estimateTokens(message(1, "irrelevant content", 37, 100)));
    }

    @Test
    void estimateFallsBackToCharsPerFourTokens() {
        assertEquals(5, SessionCompactor.estimateTokens(message(1, "x".repeat(20), null, 100)));
    }

    @Test
    void estimateIsAtLeastOneForAnyContent() {
        assertEquals(1, SessionCompactor.estimateTokens(message(1, "ab", null, 100)));
        assertEquals(1, SessionCompactor.estimateTokens(message(1, null, null, 100)));
    }

    @Test
    void boundaryIsMaxValueWhenNoTurnMessageExists() {
        List<MessageEntity> region = List.of(
                orphan(1, BlockType.TURN_REASONING, 100),
                orphan(2, BlockType.TOOL_EXECUTION, 200));
        assertEquals(Long.MAX_VALUE, SessionCompactor.retainBoundary(region, 1000),
                "a region with no TURN_MESSAGE has no retain boundary");
    }

    @Test
    void newestMessagesAreRetainedUntilTheBudgetIsHit() {
        // Three turn messages of 10 tokens each, oldest first. Budget 20 retains the newest two;
        // the boundary is the createdAt of the OLDEST retained message (id 2).
        List<MessageEntity> region = List.of(
                message(1, "a".repeat(40), null, 100),
                message(2, "b".repeat(40), null, 200),
                message(3, "c".repeat(40), null, 300));
        assertEquals(200, SessionCompactor.retainBoundary(region, 20));
    }

    @Test
    void theNewestTurnMessageAlwaysSurvivesEvenWhenItAloneBlowsTheBudget() {
        // The newest message alone exceeds retainTokens: it must still become the boundary (keep at
        // least one — never drop the live turn).
        List<MessageEntity> region = List.of(
                message(1, "old".repeat(20), null, 100),
                message(2, "y".repeat(400), null, 200));
        assertEquals(200, SessionCompactor.retainBoundary(region, 10));
    }

    @Test
    void nonMessageRowsNeverDriveTheRetainBudget() {
        // A huge TOOL_EXECUTION row between two small turn messages must not consume budget: both
        // turn messages fit 20 tokens, so the boundary is the older one (id 1).
        List<MessageEntity> region = List.of(
                message(1, "a".repeat(40), null, 100),
                orphan(2, BlockType.TOOL_EXECUTION, 150),
                message(3, "c".repeat(40), null, 300));
        assertEquals(100, SessionCompactor.retainBoundary(region, 20));
    }

    private static MessageEntity message(long id, String content, Integer tokens, long createdAt) {
        MessageEntity m = new MessageEntity();
        m.id = id;
        m.sessionId = "s";
        m.agentId = "main";
        m.role = Role.USER.dbValue();
        m.content = content;
        m.tokens = tokens;
        m.blockType = BlockType.TURN_MESSAGE.dbValue();
        m.createdAt = createdAt;
        return m;
    }

    private static MessageEntity orphan(long id, BlockType type, long createdAt) {
        MessageEntity m = message(id, "z".repeat(4000), null, createdAt);
        m.role = Role.TOOL.dbValue();
        m.blockType = type.dbValue();
        return m;
    }
}
