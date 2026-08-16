package ai.forvum.engine.compress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/**
 * Unit tests for the #197 {@link MidTurnPruner}: oversized tool results are elided head+tail under the
 * {@code compressThresholdChars} knob, stale images are replaced with the removal marker, superseded
 * thinking is stripped, and — the cache-stability invariant — the frozen seeded prefix is NEVER touched
 * and messages are only replaced in place (never inserted/removed/reordered).
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

    @Test
    void oversizedToolResultIsElidedHeadPlusTailWithinTheThreshold() {
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("question"),
                AiMessage.from("calling tool"),
                toolResult("t1", oversized(10_000))));

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertEquals(1, stats.toolResultsPruned());
        ToolExecutionResultMessage pruned = (ToolExecutionResultMessage) conversation.get(2);
        assertTrue(pruned.text().length() <= 500, "pruned text must be within the threshold");
        assertTrue(pruned.text().startsWith(HEAD), "the head must be kept");
        assertTrue(pruned.text().endsWith(TAIL), "the tail must be kept");
        assertTrue(pruned.text().contains(MidTurnPruner.ELISION_MARKER), "the middle is elided behind the marker");
        assertEquals("t1", pruned.id(), "the tool-call id must survive so request/result pairing is intact");
        assertEquals("fs.read", pruned.toolName());
        assertEquals(3, conversation.size(), "in-place replacement only — never insert/remove");
    }

    @Test
    void underThresholdToolResultIsUntouchedSameInstance() {
        ToolExecutionResultMessage small = toolResult("t1", "small result");
        List<ChatMessage> conversation = new ArrayList<>(List.of(UserMessage.from("q"), small));

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertFalse(stats.changed());
        assertSame(small, conversation.get(1), "an under-threshold result is not even rebuilt");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -500})
    void nonPositiveThresholdDisablesTheWholePass(int threshold) {
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), toolResult("t1", oversized(10_000))));
        List<ChatMessage> before = List.copyOf(conversation);

        assertFalse(MidTurnPruner.prune(conversation, 0, threshold).changed());
        assertEquals(before, conversation);
    }

    @Test
    void frozenPrefixIsNeverTouchedEvenWhenOversized() {
        // An oversized tool result INSIDE the frozen prefix (history replayed into the seed) stays
        // byte-identical — the compactor's cache-stability rule, mirrored.
        ToolExecutionResultMessage frozen = toolResult("old", oversized(10_000));
        SystemMessage system = SystemMessage.from("system prompt");
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                system, frozen, UserMessage.from("q"),
                toolResult("new", oversized(10_000))));

        PruneStats stats = MidTurnPruner.prune(conversation, 3, 500);

        assertEquals(1, stats.toolResultsPruned(), "only the region result is pruned");
        assertSame(system, conversation.get(0));
        assertSame(frozen, conversation.get(1), "the frozen oversized result is untouched");
        assertEquals(oversized(10_000), ((ToolExecutionResultMessage) conversation.get(1)).text());
        assertTrue(((ToolExecutionResultMessage) conversation.get(3)).text().length() <= 500);
    }

    @Test
    void pruningIsIdempotentAcrossRounds() {
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), toolResult("t1", oversized(10_000))));

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
    void supersededThinkingIsStrippedLatestAssistantKeepsIts() {
        AiMessage older = AiMessage.builder().text("step 1").thinking("early reasoning, superseded").build();
        AiMessage newest = AiMessage.builder().text("step 2").thinking("live reasoning").build();
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), older, toolResult("t1", "ok"), newest));

        PruneStats stats = MidTurnPruner.prune(conversation, 1, 500);

        assertEquals(1, stats.thinkingStripped());
        AiMessage strippedOlder = (AiMessage) conversation.get(1);
        assertNull(strippedOlder.thinking(), "superseded thinking is dropped");
        assertEquals("step 1", strippedOlder.text(), "the assistant text survives");
        assertSame(newest, conversation.get(3), "the newest assistant message keeps its thinking untouched");
    }

    @Test
    void thinkingFreeAssistantMessagesAreNotRebuilt() {
        AiMessage older = AiMessage.from("step 1");
        AiMessage newest = AiMessage.from("step 2");
        List<ChatMessage> conversation = new ArrayList<>(List.of(UserMessage.from("q"), older, newest));

        assertFalse(MidTurnPruner.prune(conversation, 1, 500).changed());
        assertSame(older, conversation.get(1));
    }

    @Test
    void thresholdSmallerThanTheMarkerStillHardClampsTheLength() {
        int tiny = 10; // smaller than ELISION_MARKER itself
        List<ChatMessage> conversation = new ArrayList<>(List.of(
                UserMessage.from("q"), toolResult("t1", oversized(1000))));

        MidTurnPruner.prune(conversation, 1, tiny);

        assertTrue(((ToolExecutionResultMessage) conversation.get(1)).text().length() <= tiny,
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
