package ai.forvum.engine.memory;

import ai.forvum.core.MemoryHit;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure ranking helpers for the local SQLite {@code MemoryProvider} (#175). Deterministic, no IO — the
 * provider does the DB reads and the embedding call, then hands the raw signals here to be normalized,
 * scored, floored, and capped per the agent's {@code MemoryPolicy}. Kept separate from the provider so
 * the scoring is unit-testable without a DB, a model, or a CDI boot.
 */
final class MemoryRanking {

    /** Word-splitter for keyword scoring — {@code UNICODE_CHARACTER_CLASS} so accents/CJK count as letters. */
    private static final Pattern NON_WORD = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

    private MemoryRanking() {
    }

    /**
     * Map a cosine similarity (native range {@code [-1, 1]}) into the normalized {@code [0, 1]} relevance
     * the {@link MemoryHit} contract requires. A degenerate {@code NaN} metric folds to {@code 0.0} so it
     * can never leak past the score invariant; out-of-range inputs clamp to the bounds. Note that a
     * zero/degenerate embedding yields cosine {@code 0.0} (not NaN) upstream in {@code VectorMath.cosine},
     * which maps here to {@code 0.5} (treated as orthogonal) — harmless, as real embeddings are non-zero.
     */
    static double normalizeCosine(double cosine) {
        if (Double.isNaN(cosine)) {
            return 0.0;
        }
        double normalized = (cosine + 1.0) / 2.0;
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    /**
     * A tokenized-containment relevance for episodic recall: the fraction of the query's distinct terms
     * that appear (case-insensitively) in {@code content}, in {@code [0, 1]}. A blank query or blank
     * content scores {@code 0.0} (no signal).
     */
    static double keywordScore(String query, String content) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> contentTerms = terms(content);
        long present = queryTerms.stream().filter(contentTerms::contains).count();
        return (double) present / queryTerms.size();
    }

    private static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        // Unicode-aware split so a Portuguese ("café") or CJK query is not shredded into empty tokens the
        // way ASCII \W would (which killed episodic keyword recall for non-Latin scripts).
        return Arrays.stream(NON_WORD.split(text.toLowerCase(Locale.ROOT)))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Drop every hit below {@code minScore}, sort by score descending (most relevant first), and cap at
     * {@code topK}. The mixed-tier input (semantic cosine + episodic keyword) shares the normalized
     * {@code [0, 1]} scale, so a single ordering across tiers is well-defined.
     */
    static List<MemoryHit> topHits(List<MemoryHit> hits, double minScore, int topK) {
        return hits.stream()
                .filter(h -> h.score() >= minScore)
                .sorted(Comparator.comparingDouble(MemoryHit::score).reversed())
                .limit(topK)
                .toList();
    }
}
