package ai.forvum.engine.session.compaction;

import java.util.List;

/**
 * The write-time Compress seam (CE Compress, ULTRAPLAN section 1.4 / 7.2 item 20) session compaction
 * uses to fold a run of oldest turns into a single dense summary message before they leave the live
 * window. It is the same small-and-fast summarization role the M18 {@code reduce} node uses (default
 * Ollama {@code qwen3:1.7b}), exposed as an injectable CDI contract so a test can substitute a
 * deterministic stub (no live model) via a {@code @Mock}/{@code @Alternative} override.
 *
 * @see DefaultSummarizer the production implementation backed by the proxy model
 */
public interface Summarizer {

    /**
     * Summarize the dropped turns' textual content into one compact paragraph that re-enters the window
     * as a single summary message.
     *
     * @param droppedContents the {@code content} of each message being compacted out, oldest first
     * @return a single summary string (never null; empty is allowed when there is nothing to summarize)
     */
    String summarize(List<String> droppedContents);
}
