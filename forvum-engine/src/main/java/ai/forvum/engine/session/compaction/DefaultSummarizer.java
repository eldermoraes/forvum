package ai.forvum.engine.session.compaction;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.routing.LlmSelector;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * The production {@link Summarizer}: folds the dropped turns through the small-and-fast proxy model
 * (default {@code ollama:qwen3:1.7b}, the same model the M18 {@code reduce} Compress node uses,
 * ULTRAPLAN section 1.4) resolved via {@link LlmSelector}. The model is NOT invented here — it reuses
 * the existing routing seam, fallback-wrapped and ledgered into {@code provider_calls} like any turn.
 *
 * <p>Tests never reach this bean — they bind a deterministic stub {@link Summarizer} so no live model
 * is contacted (CLAUDE.md section 4 / 11).
 */
@ApplicationScoped
public class DefaultSummarizer implements Summarizer {

    private static final String SYSTEM_PROMPT =
            "You compress prior conversation turns into a single dense summary paragraph. Preserve "
          + "facts, decisions, names, and open questions; drop pleasantries and verbatim wording. "
          + "Reply with the summary only.";

    @Inject
    LlmSelector llmSelector;

    /** The proxy model used for compaction summarization; defaults to the §1.4 small-and-fast model. */
    @ConfigProperty(name = "forvum.compaction.summarizer-model", defaultValue = "ollama:qwen3:1.7b")
    String summarizerModel;

    @Override
    public String summarize(List<String> droppedContents) {
        if (droppedContents.isEmpty()) {
            return "";
        }
        ChatModel model = llmSelector.resolve(ModelRef.parse(summarizerModel), "compaction", "compaction");
        return model.chat(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(String.join("\n\n", droppedContents))).aiMessage().text();
    }
}
