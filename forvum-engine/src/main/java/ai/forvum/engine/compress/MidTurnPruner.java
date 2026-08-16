package ai.forvum.engine.compress;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * #197 — mid-turn context pruning for the supervisor tool loop (the Compress pillar WITHIN a turn).
 * The between-turn {@code SessionCompactor} and the #176 {@code BoundedCompressor} bound what enters
 * the window at turn boundaries and at the retrieved-memory/worker-digest seams, but a long multi-round
 * turn accumulates oversized {@code tool_loop} results inside the single turn with no bound at all.
 * This pruner is that bound: a cheap, pure in-memory pass over the turn's mutable conversation, run at
 * every {@code generate} entry — no model calls, no IO, no reflection (native-clean).
 *
 * <p>Three prunes, all governed by the existing {@code MemoryPolicy.compressThresholdChars} knob
 * ({@code <= 0} disables the whole pass, which also keeps replay #57 deterministic):
 * <ul>
 *   <li><strong>Oversized tool results</strong> — a {@link ToolExecutionResultMessage} whose text exceeds
 *       the threshold keeps its head and tail with the middle elided behind {@link #ELISION_MARKER}
 *       (head + tail, unlike the head-only #176 fallback, because a mid-turn tool result's trailing
 *       chars — exit status, summary lines — are often the useful part). The pruned text never exceeds
 *       the threshold, so a pruned result is never re-pruned (idempotent across rounds).</li>
 *   <li><strong>Stale images</strong> — {@link ImageContent} in a {@link UserMessage} outside the
 *       {@link #IMAGE_RECENCY_WINDOW} newest image-bearing messages of the prunable region is replaced
 *       with a {@link TextContent} marker. Seam note (#185): today images NEVER enter the supervisor
 *       message list — {@code EngineMediaAnalysis} confines {@code ImageContent} to an isolated media
 *       sub-generation and only its text digest rides in the turn — so this arm is dormant; it prunes
 *       the message-content shape that exists ({@code UserMessage.contents()}) for the day a channel or
 *       tool injects images into the turn directly.</li>
 *   <li><strong>Superseded thinking</strong> — the between-turn compactor's {@code BlockType} orphan
 *       stripping, mirrored in-turn: {@code AiMessage.thinking()} on every assistant message EXCEPT the
 *       newest one is dropped (the newest may still be driving the current tool calls). Providers that
 *       emit no thinking make this arm a no-op.</li>
 * </ul>
 *
 * <p><strong>Prompt-cache stability (the compactor's rule, mirrored):</strong> the pruner only ever
 * REPLACES messages in place at indexes {@code >= frozenPrefixSize} — it never inserts, removes, or
 * reorders, and never reads (let alone touches) the seeded prefix — so the cached prefix bytes and the
 * tool-request/tool-result pairing are undisturbed and pruning is tail-region-only.
 */
public final class MidTurnPruner {

    private static final Logger LOG = Logger.getLogger(MidTurnPruner.class);

    /**
     * The fixed marker standing in for an elided tool-result middle. OUR literal — never derived from
     * the (untrusted) tool output — carrying no delimiter; the omitted-char count goes to the safe log,
     * never into the prompt (the #176 privacy posture).
     */
    public static final String ELISION_MARKER =
            "\n[Forvum: middle of oversized tool result elided during mid-turn context pruning]\n";

    /** The fixed marker replacing an image pruned out of the window (#197). */
    public static final String IMAGE_REMOVED_MARKER = "[image removed during context pruning]";

    /** How many of the region's newest image-bearing messages keep their images. */
    static final int IMAGE_RECENCY_WINDOW = 1;

    private MidTurnPruner() {
    }

    /** Content-free counters for one pass — observability without recording any payload. */
    public record PruneStats(int toolResultsPruned, int imagesRemoved, int thinkingStripped) {

        static final PruneStats NONE = new PruneStats(0, 0, 0);

        /** Whether the pass changed anything. */
        public boolean changed() {
            return toolResultsPruned > 0 || imagesRemoved > 0 || thinkingStripped > 0;
        }
    }

    /**
     * Prune {@code conversation} in place: messages at indexes {@code >= frozenPrefixSize} only, replaced
     * one-for-one (never inserted/removed). {@code thresholdChars <= 0} disables the pass entirely.
     * Returns content-free counters.
     */
    public static PruneStats prune(List<ChatMessage> conversation, int frozenPrefixSize, int thresholdChars) {
        if (thresholdChars <= 0 || conversation.size() <= frozenPrefixSize) {
            return PruneStats.NONE;
        }
        int from = Math.max(0, frozenPrefixSize);
        int lastAssistant = lastIndexOf(conversation, from, AiMessage.class);
        int imageKeepFloor = imageKeepFloor(conversation, from);

        int toolResultsPruned = 0;
        int imagesRemoved = 0;
        int thinkingStripped = 0;
        for (int i = from; i < conversation.size(); i++) {
            ChatMessage message = conversation.get(i);
            if (message instanceof ToolExecutionResultMessage result
                    && result.text() != null && result.text().length() > thresholdChars) {
                conversation.set(i, ToolExecutionResultMessage.from(
                        result.id(), result.toolName(), elideMiddle(result.text(), thresholdChars)));
                toolResultsPruned++;
            } else if (message instanceof UserMessage user && i < imageKeepFloor && hasImage(user)) {
                UserMessage stripped = stripImages(user);
                imagesRemoved += countImages(user);
                conversation.set(i, stripped);
            } else if (message instanceof AiMessage assistant && i < lastAssistant
                    && assistant.thinking() != null && !assistant.thinking().isBlank()) {
                conversation.set(i, assistant.toBuilder().thinking(null).build());
                thinkingStripped++;
            }
        }
        if (toolResultsPruned > 0 || imagesRemoved > 0 || thinkingStripped > 0) {
            // Safe metric: counts only — never the pruned payload (the #176 privacy contract).
            LOG.debugf("Mid-turn pruning: %d tool results elided, %d images removed, %d thinking blocks stripped",
                    toolResultsPruned, imagesRemoved, thinkingStripped);
        }
        return new PruneStats(toolResultsPruned, imagesRemoved, thinkingStripped);
    }

    /**
     * Elide the middle of {@code text} down to at most {@code max} chars, keeping an equal head and tail
     * around {@link #ELISION_MARKER}. When {@code max} is smaller than the marker itself the result is
     * hard-clamped to {@code max} (the #176 length guarantee wins over marker integrity).
     */
    static String elideMiddle(String text, int max) {
        if (text.length() <= max) {
            return text; // already within budget (defensive; callers pass over-budget text)
        }
        int keep = Math.max(0, max - ELISION_MARKER.length());
        int head = keep / 2;
        int tail = keep - head;
        String out = text.substring(0, head) + ELISION_MARKER + text.substring(text.length() - tail);
        return out.length() <= max ? out : out.substring(0, max);
    }

    /**
     * The index at/after which image-bearing messages keep their images: the index of the region's
     * {@link #IMAGE_RECENCY_WINDOW}-th newest image-bearing message ({@code Integer.MAX_VALUE} strips
     * every image when the region holds more than the window; {@code from} keeps them all otherwise).
     */
    private static int imageKeepFloor(List<ChatMessage> conversation, int from) {
        int seen = 0;
        for (int i = conversation.size() - 1; i >= from; i--) {
            if (conversation.get(i) instanceof UserMessage user && hasImage(user) && ++seen == IMAGE_RECENCY_WINDOW) {
                return i;
            }
        }
        return from; // fewer image-bearing messages than the window: keep them all
    }

    private static boolean hasImage(UserMessage message) {
        return message.contents().stream().anyMatch(ImageContent.class::isInstance);
    }

    private static int countImages(UserMessage message) {
        return (int) message.contents().stream().filter(ImageContent.class::isInstance).count();
    }

    /** Rebuild {@code message} with every {@link ImageContent} replaced by the removal marker. */
    private static UserMessage stripImages(UserMessage message) {
        List<Content> contents = new ArrayList<>(message.contents().size());
        for (Content content : message.contents()) {
            contents.add(content instanceof ImageContent
                    ? TextContent.from(IMAGE_REMOVED_MARKER) : content);
        }
        return message.toBuilder().contents(contents).build();
    }

    /** The last index {@code >= from} holding an instance of {@code type}, or {@code -1}. */
    private static int lastIndexOf(List<ChatMessage> conversation, int from, Class<?> type) {
        for (int i = conversation.size() - 1; i >= from; i--) {
            if (type.isInstance(conversation.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
