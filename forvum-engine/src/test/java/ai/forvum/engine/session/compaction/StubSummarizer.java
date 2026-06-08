package ai.forvum.engine.session.compaction;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;

/**
 * Deterministic test {@link Summarizer} that contacts no live model: it folds the dropped contents into
 * a fixed, inspectable string so compaction tests assert the exact invariants (CLAUDE.md section 4 / 11).
 * A globally-enabled {@code @Alternative} ({@code @Priority}) so it replaces {@link DefaultSummarizer}
 * wherever {@link SessionCompactor} injects a {@link Summarizer} under {@code @QuarkusTest}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class StubSummarizer implements Summarizer {

    static final String PREFIX = "STUB-SUMMARY:";

    @Override
    public String summarize(List<String> droppedContents) {
        return PREFIX + droppedContents.size() + ":" + String.join("|", droppedContents);
    }
}
