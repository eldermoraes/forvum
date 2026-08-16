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
import java.util.Set;

/**
 * #197 — mid-turn context pruning for the supervisor tool loop (the Compress pillar WITHIN a turn).
 * The between-turn {@code SessionCompactor} and the #176 {@code BoundedCompressor} bound what enters
 * the window at turn boundaries and at the retrieved-memory/worker-digest seams, but a long multi-round
 * turn accumulates oversized {@code tool_loop} results inside the single turn with no bound at all.
 * This pruner is that bound: a cheap, pure in-memory pass over the turn's mutable conversation, run at
 * every {@code generate} entry — no model calls, no IO, no reflection (native-clean).
 *
 * <p>Two prunes, both governed by the existing {@code MemoryPolicy.compressThresholdChars} knob
 * ({@code <= 0} disables the whole pass, which also keeps replay #57 deterministic):
 * <ul>
 *   <li><strong>Oversized tool results</strong> — a {@link ToolExecutionResultMessage} whose text exceeds
 *       the threshold keeps its head and tail with the middle elided behind {@link #ELISION_MARKER}
 *       (head + tail, unlike the head-only #176 fallback, because a mid-turn tool result's trailing
 *       chars — exit status, summary lines — are often the useful part). The pruned text never exceeds
 *       the threshold, so a pruned result is never re-pruned (idempotent across rounds). Two protections
 *       bound WHAT may be elided (the #197 audit remediation): the {@link #KEEP_LAST_ASSISTANTS}
 *       <em>recency window</em> (D3.3) — a result at or after the cutoff (the index of the
 *       {@code KEEP_LAST_ASSISTANTS}-th {@link AiMessage} counted from the end of the WHOLE list) is
 *       never elided, structurally guaranteeing the current round's results reach the model whole
 *       (fewer than {@code KEEP_LAST_ASSISTANTS} assistant messages ⇒ no cutoff ⇒ no result prunable);
 *       and {@link #PROTECTED_TOOL_NAMES} (D3.4) — the {@code update_plan} result IS the model's
 *       same-turn plan surface, so it is never elided regardless of age or size.</li>
 *   <li><strong>Stale images</strong> — {@link ImageContent} in a {@link UserMessage} outside the
 *       {@link #IMAGE_RECENCY_WINDOW} newest image-bearing messages of the prunable region is replaced
 *       with a {@link TextContent} marker. <em>As-built note (#197 audit):</em> this arm targets
 *       {@code UserMessage.contents()} and is DORMANT in production — today images never enter the
 *       supervisor message list ({@code EngineMediaAnalysis} confines {@code ImageContent} to an
 *       isolated media sub-generation, #185, and only its text digest rides in the turn). It exists for
 *       the day a channel or tool injects images into the turn directly and is exercised only by unit
 *       tests.</li>
 * </ul>
 *
 * <p>The original third arm — stripping superseded {@code AiMessage.thinking()} — was REMOVED by the
 * #197 audit remediation: it rebuilt assistant messages already transmitted in a prior round of the
 * same turn, invalidating every prompt-cache prefix from the first assistant message onward, and a
 * provider that requires thinking blocks paired with their {@code tool_use} (Anthropic extended
 * thinking) rejects the request outright. D7 settled thinking-stripping as explicitly NOT in v1.
 *
 * <p><strong>Prompt-cache stability (the compactor's rule, mirrored):</strong> the pruner only ever
 * REPLACES messages in place at indexes {@code >= frozenPrefixSize} — it never inserts, removes, or
 * reorders, and never reads (let alone touches) the seeded prefix — so the cached prefix bytes and the
 * tool-request/tool-result pairing are undisturbed and pruning is tail-region-only. A result is mutated
 * at most ONCE (the moment it exits the recency window), then byte-stable across rounds (trim-once
 * monotonicity, D9): an already-sent message is never rewritten.
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

    /**
     * The recency window for tool-result elision (D3.3, OpenClaw cutoff parity): a tool result at or
     * after the index of the {@code KEEP_LAST_ASSISTANTS}-th newest {@link AiMessage} is never elided,
     * so the results of the last {@code KEEP_LAST_ASSISTANTS} rounds — including the current one, whose
     * results sit after the last assistant message — always reach the model whole. An engine constant
     * (OpenClaw's default), no config.
     */
    public static final int KEEP_LAST_ASSISTANTS = 3;

    /**
     * Tool names whose results are never elided regardless of age or size (D3.4): {@code update_plan}'s
     * rendered checklist IS the model's same-turn plan surface (#190) — a corrupted mid-turn checklist
     * under a small threshold would derail the whole plan-following turn.
     */
    static final Set<String> PROTECTED_TOOL_NAMES = Set.of("update_plan");

    private MidTurnPruner() {
    }

    /** Content-free counters for one pass — observability without recording any payload. */
    public record PruneStats(int toolResultsPruned, int imagesRemoved) {

        static final PruneStats NONE = new PruneStats(0, 0);

        /** Whether the pass changed anything. */
        public boolean changed() {
            return toolResultsPruned > 0 || imagesRemoved > 0;
        }
    }

    /**
     * Prune {@code conversation} in place: messages at indexes {@code >= frozenPrefixSize} only, replaced
     * one-for-one (never inserted/removed). {@code thresholdChars <= 0} disables the pass entirely.
     * Returns content-free counters.
     */
    public static PruneStats prune(List<ChatMessage> conversation, int frozenPrefixSize, int thresholdChars) {
        return prune(conversation, frozenPrefixSize, thresholdChars, PROTECTED_TOOL_NAMES);
    }

    /**
     * The {@link #prune(List, int, int)} pass with an explicit protected-tool-name set — the package-private
     * test seam (OQ-A), so the protection rule is testable independently of the production constant.
     */
    static PruneStats prune(List<ChatMessage> conversation, int frozenPrefixSize, int thresholdChars,
            Set<String> protectedTools) {
        if (thresholdChars <= 0 || conversation.size() <= frozenPrefixSize) {
            return PruneStats.NONE;
        }
        int from = Math.max(0, frozenPrefixSize);
        // D3.3 cutoff: the KEEP_LAST_ASSISTANTS-th AiMessage counted from the END of the WHOLE list (a
        // seeded-history assistant message can only move the cutoff earlier — more protective). A result
        // is elidable only STRICTLY BEFORE the cutoff; fewer assistants than the window ⇒ no cutoff ⇒
        // no result prunable.
        int cutoff = recencyCutoff(conversation);
        int imageKeepFloor = imageKeepFloor(conversation, from);

        int toolResultsPruned = 0;
        int imagesRemoved = 0;
        for (int i = from; i < conversation.size(); i++) {
            ChatMessage message = conversation.get(i);
            if (message instanceof ToolExecutionResultMessage result
                    && cutoff >= 0 && i < cutoff
                    && !protectedTools.contains(result.toolName())
                    && result.text() != null && result.text().length() > thresholdChars) {
                conversation.set(i, ToolExecutionResultMessage.from(
                        result.id(), result.toolName(), elideMiddle(result.text(), thresholdChars)));
                toolResultsPruned++;
            } else if (message instanceof UserMessage user && i < imageKeepFloor && hasImage(user)) {
                UserMessage stripped = stripImages(user);
                imagesRemoved += countImages(user);
                conversation.set(i, stripped);
            }
        }
        if (toolResultsPruned > 0 || imagesRemoved > 0) {
            // Safe metric: counts only — never the pruned payload (the #176 privacy contract).
            LOG.debugf("Mid-turn pruning: %d tool results elided, %d images removed",
                    toolResultsPruned, imagesRemoved);
        }
        return new PruneStats(toolResultsPruned, imagesRemoved);
    }

    /**
     * The elision cutoff (D3.3): the index of the {@link #KEEP_LAST_ASSISTANTS}-th newest
     * {@link AiMessage} across the WHOLE conversation, or {@code -1} when fewer assistant messages exist
     * (nothing is prunable yet).
     */
    private static int recencyCutoff(List<ChatMessage> conversation) {
        int seen = 0;
        for (int i = conversation.size() - 1; i >= 0; i--) {
            if (conversation.get(i) instanceof AiMessage && ++seen == KEEP_LAST_ASSISTANTS) {
                return i;
            }
        }
        return -1;
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
}
