package ai.forvum.engine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryTier;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pure ranking helpers for the local SQLite {@code MemoryProvider} (#175): normalize a cosine into the
 * {@code [0,1]} contract, score episodic recall by query-term overlap, and merge/floor/cap a mixed-tier
 * hit set per {@code MemoryPolicy}. No DB, no CDI, no model — deterministic unit coverage.
 */
class MemoryRankingTest {

    @Test
    void normalizeCosineMapsTheMetricRangeIntoZeroOne() {
        assertEquals(0.0, MemoryRanking.normalizeCosine(-1.0), 1e-9, "-1 is least similar");
        assertEquals(0.5, MemoryRanking.normalizeCosine(0.0), 1e-9, "orthogonal is the midpoint");
        assertEquals(1.0, MemoryRanking.normalizeCosine(1.0), 1e-9, "identical is 1.0");
    }

    @Test
    void normalizeCosineClampsOutOfRangeAndNaNIntoBounds() {
        assertEquals(0.0, MemoryRanking.normalizeCosine(Double.NaN), 1e-9,
                "NaN must not leak past the MemoryHit [0,1] score contract");
        assertEquals(1.0, MemoryRanking.normalizeCosine(1.5), 1e-9);
        assertEquals(0.0, MemoryRanking.normalizeCosine(-2.0), 1e-9);
    }

    @Test
    void keywordScoreIsTheFractionOfQueryTermsPresentInTheContent() {
        assertEquals(1.0, MemoryRanking.keywordScore("favorite color", "my favorite color is blue"), 1e-9);
        assertEquals(0.5, MemoryRanking.keywordScore("favorite fruit", "my favorite color is blue"), 1e-9);
        assertEquals(0.0, MemoryRanking.keywordScore("home city", "my favorite color is blue"), 1e-9);
    }

    @Test
    void keywordScoreIsCaseInsensitiveAndZeroForABlankQuery() {
        assertEquals(1.0, MemoryRanking.keywordScore("BLUE", "the sky is Blue"), 1e-9);
        assertEquals(0.0, MemoryRanking.keywordScore("", "anything"), 1e-9, "no query terms -> no signal");
        assertEquals(0.0, MemoryRanking.keywordScore("x", ""), 1e-9, "no content -> no signal");
    }

    @Test
    void keywordScoreHandlesAccentedAndNonLatinTermsForMultilingualRecall() {
        // Unicode-aware tokenization: Portuguese accents survive (not shredded to "caf"), and a
        // whitespace-delimited CJK term matches — episodic recall must work for a PT/multilingual user.
        assertEquals(1.0, MemoryRanking.keywordScore("café", "eu tomei um café hoje"), 1e-9);
        assertEquals(1.0, MemoryRanking.keywordScore("日本", "eu moro no 日本 agora"), 1e-9);
    }

    @Test
    void topHitsDropsBelowMinScoreSortsDescendingAndCapsAtTopK() {
        List<MemoryHit> hits = List.of(
                new MemoryHit(MemoryTier.SEMANTIC, "low", 0.2, "s:low"),
                new MemoryHit(MemoryTier.SEMANTIC, "high", 0.9, "s:high"),
                new MemoryHit(MemoryTier.EPISODIC, "mid", 0.6, "e:mid"));

        List<MemoryHit> ranked = MemoryRanking.topHits(hits, 0.5, 2);

        assertEquals(2, ranked.size(), "topK caps the returned count");
        assertEquals("high", ranked.get(0).content(), "highest score ranks first");
        assertEquals("mid", ranked.get(1).content());
        assertTrue(ranked.stream().noneMatch(h -> "low".equals(h.content())),
                "a hit below minScore must be dropped");
    }

    @Test
    void topHitsWithZeroFloorKeepsEverythingUpToTopK() {
        List<MemoryHit> hits = List.of(
                new MemoryHit(MemoryTier.SEMANTIC, "a", 0.0, "s:a"),
                new MemoryHit(MemoryTier.SEMANTIC, "b", 0.1, "s:b"));
        assertEquals(2, MemoryRanking.topHits(hits, 0.0, 8).size(),
                "minScore 0.0 is 'no floor' — a 0.0 hit still qualifies");
    }
}
