package ai.forvum.engine.compress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.compress.MidTurnPruner.PruneStats;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Unit tests for the #197 {@link MidTurnPruner} (post-audit shape): oversized tool results are elided
 * head+tail under the {@code compressThresholdChars} knob but ONLY outside the
 * {@link MidTurnPruner#KEEP_LAST_ASSISTANTS} recency window (D3.3) and never for a
 * {@link MidTurnPruner#PROTECTED_TOOL_NAMES protected tool} (D3.4); stale images are replaced with the
 * removal marker; and — the cache-stability invariants — the frozen seeded prefix is NEVER touched,
 * messages are only replaced in place (never inserted/removed/reordered), an already-sent assistant
 * message is never rebuilt (the thinking()-strip arm is GONE), and a result is mutated at most once.
 */
class MidTurnPrunerTest {

    private static final String HEAD = "HEAD-SENTINEL ";
    private static final String TAIL = " TAIL-SENTINEL";

    private static String oversized(int totalChars) {
        return HEAD + "x".repeat(Math.max(0, totalChars - HEAD.length() - TAIL.length())) + TAIL;
    }

    private static ToolExecutionResultMessage toolResult(String id, String text) {
        return ToolExecutionResultMessage.from(id, "fs.read", text);
    }

    /**
     * A canonical multi-round tool loop: user, a1, r1(oversized), a2, r2, a3, r3, a4. Assistants counted
     * from the end put the D3.3 cutoff at a2 (index 3), so ONLY r1 (index 2) is outside the recency
     * window and elidable.
     */
    private static List<ChatMessage> multiRoundLoop(ToolExecutionResultMessage r1) {
        return new ArrayList<>(List.of(
                UserMessage.from("question"),
                AiMessage.from("round 1"),
                r1,
                AiMessage.from("round 2"),
                toolResult("t2", "ok 2"),
                AiMessage.from("round 3"),
                toolResult("t3", "ok 3"),
                AiMessage.from("round 4")));
    }

    @Test
    void oversizedToolResultOutsideTheWindowIsElidedHeadPlusTailWithinTheThreshold() {
        List<ChatMessage> conversation = multiRoundLoop(toolResult("t1", oversized(10_000)));

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertEquals(1, stats.toolResultsPruned());
        ToolExecutionResultMessage pruned = (ToolExecutionResultMessage) conversation.get(2);
        assertTrue(pruned.text().length() <= 500, "pruned text must be within the threshold");
        assertTrue(pruned.text().startsWith(HEAD), "the head must be kept");
        assertTrue(pruned.text().endsWith(TAIL), "the tail must be kept");
        assertTrue(pruned.text().contains(MidTurnPruner.ELISION_MARKER), "the middle is elided behind the marker");
        assertEquals("t1", pruned.id(), "the tool-call id must survive so request/result pairing is intact");
        assertEquals("fs.read", pruned.toolName());
        assertEquals(8, conversation.size(), "in-place replacement only — never insert/remove");
    }

    @Test
    void oversizedToolResultWithinTheRecencyWindowIsNeverElided() {
        // r2 and r3 sit AT/AFTER the cutoff (a2, index 3): the last KEEP_LAST_ASSISTANTS rounds' results
        // must reach the model whole no matter their size (D3.3).
        ToolExecutionResultMessage recent = toolResult("t3", oversized(10_000));
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("question"),
                AiMessage.from("round 1"),
                toolResult("t1", "ok 1"),
                AiMessage.from("round 2"),
                toolResult("t2", "ok 2"),
                AiMessage.from("round 3"),
                recent,
                AiMessage.from("round 4")));

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertSame(recent, conversation.get(6), "a within-window result is not even rebuilt");
    }

    @Test
    void fewerAssistantsThanTheWindowMeansNoResultIsPrunable() {
        // Only 2 assistant messages: no cutoff exists yet, so even a huge early result stays whole.
        ToolExecutionResultMessage big = toolResult("t1", oversized(10_000));
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), AiMessage.from("a1"), big, AiMessage.from("a2")));

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertSame(big, conversation.get(2));
    }

    @Test
    void protectedToolResultIsNeverElidedRegardlessOfAgeAndSize() {
        // update_plan's rendered checklist IS the plan surface (D3.4) — outside the window, oversized,
        // and still untouched.
        ToolExecutionResultMessage plan =
                ToolExecutionResultMessage.from("t1", "update_plan", oversized(10_000));
        List<ChatMessage> conversation = multiRoundLoop(plan);

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertSame(plan, conversation.get(2), "the protected result is not even rebuilt");
    }

    @Test
    void protectedToolNamesSeamGovernsTheProtection() {
        // OQ-A test seam: the same shape prunes with an empty protected set and holds with the tool named.
        List<ChatMessage> prunable = multiRoundLoop(
                ToolExecutionResultMessage.from("t1", "my.tool", oversized(10_000)));
        assertEquals(1, MidTurnPruner.prune(prunable, 1, 500, Set.of()).toolResultsPruned());

        List<ChatMessage> held = multiRoundLoop(
                ToolExecutionResultMessage.from("t1", "my.tool", oversized(10_000)));
        assertFalse(MidTurnPruner.prune(held, 1, 500, Set.of("my.tool")).changed());
    }

    @Test
    void alreadySentAssistantMessagesAreByteStableAcrossConsecutiveRounds() {
        // The prompt-cache invariant the removed thinking()-strip arm violated: round N's assistant
        // messages (including their thinking payloads) are IDENTICAL instances at round N+1.
        AiMessage a1 = AiMessage.builder().text("round 1").thinking("early reasoning").build();
        AiMessage a2 = AiMessage.builder().text("round 2").thinking("later reasoning").build();
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"),
                a1,
                toolResult("t1", oversized(10_000)),
                a2,
                toolResult("t2", "ok"),
                AiMessage.from("round 3"),
                toolResult("t3", "ok"),
                AiMessage.from("round 4")));

        MidTurnPruner.prune(conversation, 1, 500);
        ChatMessage prunedResult = conversation.get(2);
        MidTurnPruner.prune(conversation, 1, 500); // the next generate round

        assertSame(a1, conversation.get(1), "a sent assistant message is never rebuilt");
        assertSame(a2, conversation.get(3), "thinking payloads are never stripped (D7: out of v1)");
        assertEquals("early reasoning", ((AiMessage) conversation.get(1)).thinking());
        assertSame(prunedResult, conversation.get(2),
                "a result is mutated at most once, then byte-stable (trim-once monotonicity)");
    }

    @Test
    void underThresholdToolResultIsUntouchedSameInstance() {
        ToolExecutionResultMessage small = toolResult("t1", "small result");
        List<ChatMessage> conversation = multiRoundLoop(small);

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertFalse(stats.changed());
        assertSame(small, conversation.get(2), "an under-threshold result is not even rebuilt");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -500})
    void nonPositiveThresholdDisablesTheWholePass(int threshold) {
        List<ChatMessage> conversation = multiRoundLoop(toolResult("t1", oversized(10_000)));
        List<ChatMessage> before = List.copyOf(conversation);

        assertFalse(MidTurnPruner.prune(conversation, 0, threshold).changed());
        assertEquals(before, conversation);
    }

    @Test
    void frozenPrefixIsNeverTouchedEvenWhenOversized() {
        // An oversized tool result INSIDE the frozen prefix (history replayed into the seed) stays
        // byte-identical — the compactor's cache-stability rule, mirrored. The region result sits
        // outside the recency window (four assistants follow it), so it IS pruned.
        ToolExecutionResultMessage frozen = toolResult("old", oversized(10_000));
        SystemMessage system = SystemMessage.from("system prompt");
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                system, frozen, UserMessage.from("q"),
                AiMessage.from("round 1"),
                toolResult("new", oversized(10_000)),
                AiMessage.from("round 2"),
                AiMessage.from("round 3"),
                AiMessage.from("round 4")));

        PruneStats stats = MidTurnPruner.prune(conversation, 3, 500);

        assertEquals(1, stats.toolResultsPruned(), "only the region result is pruned");
        assertSame(system, conversation.get(0));
        assertSame(frozen, conversation.get(1), "the frozen oversized result is untouched");
        assertEquals(oversized(10_000), ((ToolExecutionResultMessage) conversation.get(1)).text());
        assertTrue(((ToolExecutionResultMessage) conversation.get(4)).text().length() <= 500);
    }

    @Test
    void pruningIsIdempotentAcrossRounds() {
        List<ChatMessage> conversation = multiRoundLoop(toolResult("t1", oversized(10_000)));

        assertTrue(MidTurnPruner.prune(conversation, 1, 500).changed());
        List<ChatMessage> afterFirst = List.copyOf(conversation);

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed(),
                "a second pass (the next generate round) must change nothing");
        assertEquals(afterFirst, conversation);
    }

    @Test
    void imageOutsideTheRecencyWindowIsReplacedWithTheMarkerNewestKept() {
        ImageContent oldImage = ImageContent.from("aWJj", "image/png");
        ImageContent newImage = ImageContent.from("bXc=", "image/png");
        UserMessage older = UserMessage.from(TextContent.from("look at this"), oldImage);
        UserMessage newer = UserMessage.from(TextContent.from("and this"), newImage);
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), older, AiMessage.from("ok"), newer));

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertEquals(1, stats.imagesRemoved());
        UserMessage prunedOlder = (UserMessage) conversation.get(1);
        assertTrue(prunedOlder.contents().stream().noneMatch(ImageContent.class::isInstance),
                "the stale image is gone");
        assertTrue(prunedOlder.contents().stream()
                        .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                        .anyMatch(t -> MidTurnPruner.IMAGE_REMOVED_MARKER.equals(t.text())),
                "replaced by the removal marker");
        assertEquals("look at this", ((TextContent) prunedOlder.contents().get(0)).text(),
                "sibling text content survives");
        assertSame(newer, conversation.get(3), "the newest image-bearing message keeps its image untouched");
    }

    @Test
    void singleImageWithinTheWindowIsKept() {
        UserMessage withImage = UserMessage.from(TextContent.from("see"), ImageContent.from("aWc=", "image/png"));
        List<ChatMessage> conversation = new ArrayList<>(List.of(UserMessage.from("q"), withImage));

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertSame(withImage, conversation.get(1));
    }

    @Test
    void thresholdSmallerThanTheMarkerStillHardClampsTheLength() {
        int tiny = 10; // smaller than ELISION_MARKER itself
        List<ChatMessage> conversation = multiRoundLoop(toolResult("t1", oversized(1000)));

        MidTurnPruner.prune(conversation, 1, tiny);

        assertTrue(((ToolExecutionResultMessage) conversation.get(2)).text().length() <= tiny,
                "the length guarantee wins over marker integrity");
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 750, 2_000, 100_000})
    void elideMiddleNeverExceedsTheThresholdForRandomizedSizes(int size) {
        Random random = new Random(42); // fixed seed — reproducible (CLAUDE.md section 11)
        StringBuilder text = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            text.append((char) ('a' + random.nextInt(26)));
        }
        String elided = MidTurnPruner.elideMiddle(text.toString(), 500);
        assertTrue(elided.length() <= 500);
        assertTrue(elided.contains(MidTurnPruner.ELISION_MARKER));
        assertTrue(elided.startsWith(text.substring(0, 10)), "head chars are preserved in order");
        assertTrue(elided.endsWith(text.substring(size - 10)), "tail chars are preserved in order");
    }

    @Test
    void emptyRegionIsANoop() {
        List<ChatMessage> conversation = new ArrayList<>(List.of(UserMessage.from("q")));
        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertFalse(MidTurnPruner.prune(conversation, 5, 500).changed(), "prefix beyond the list is safe");
    }
}
