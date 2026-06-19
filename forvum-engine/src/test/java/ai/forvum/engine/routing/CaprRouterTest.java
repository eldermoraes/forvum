package ai.forvum.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ModelRef;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit checks for the deterministic blended-score reorder (P3-4 #52, DR-4c [DP-8]). Pure: no CDI, no DB —
 * the router takes the candidate order + a per-model health map directly. The [M18] green-for-wrong-reason
 * guard: every assertion pins the ACTUAL reordered list, not a coincidence (the down-rank tests would
 * still pass under an identity reorder only if the seeded order already matched — it does not).
 */
class CaprRouterTest {

    private static final ModelRef HEALTHY = new ModelRef("ollama", "good");
    private static final ModelRef SAGGING = new ModelRef("ollama", "bad");
    private static final ModelRef MIDDLE = new ModelRef("ollama", "mid");

    private static CaprRouter router() {
        return new CaprRouter(true, 0.7, 3);
    }

    private static Map<ModelRef, ModelHealth> health(ModelHealth... hs) {
        Map<ModelRef, ModelHealth> m = new HashMap<>();
        for (ModelHealth h : hs) {
            m.put(h.ref(), h);
        }
        return m;
    }

    /** Acceptance: a low-pass-rate model declared FIRST is down-ranked below a healthy fallback. */
    @Test
    void saggingPrimaryIsDownRankedBelowHealthyFallback() {
        // SAGGING is the declared primary but fails 9/10; HEALTHY passes 10/10.
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 9),
                new ModelHealth(HEALTHY, 10, 0));

        List<ModelRef> ordered = router().reorder(List.of(SAGGING, HEALTHY), health);

        // The reorder MUST flip the declared order (proves it is not a pass-through).
        assertEquals(List.of(HEALTHY, SAGGING), ordered);
    }

    /** A healthy primary that stays healthy keeps its operator-preference lead. */
    @Test
    void healthyPrimaryKeepsLead() {
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(HEALTHY, 10, 0),
                new ModelHealth(SAGGING, 10, 9));

        List<ModelRef> ordered = router().reorder(List.of(HEALTHY, SAGGING), health);

        assertEquals(List.of(HEALTHY, SAGGING), ordered);
    }

    /** Three models sort strictly by pass rate, descending. */
    @Test
    void threeModelsSortByDescendingPassRate() {
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 9),  // 0.10
                new ModelHealth(MIDDLE, 10, 5),   // 0.50
                new ModelHealth(HEALTHY, 10, 1)); // 0.90

        List<ModelRef> ordered = router().reorder(List.of(SAGGING, MIDDLE, HEALTHY), health);

        assertEquals(List.of(HEALTHY, MIDDLE, SAGGING), ordered);
    }

    /** Cold-start: no health data → declared order is preserved (no model is starved). */
    @Test
    void coldStartPreservesDeclaredOrder() {
        List<ModelRef> declared = List.of(SAGGING, HEALTHY, MIDDLE);
        assertEquals(declared, router().reorder(declared, Map.of()));
    }

    /** A model below the min-attempts floor scores the neutral prior — its sag is ignored. */
    @Test
    void belowMinAttemptsIsNotDownRanked() {
        // SAGGING has only 2 attempts (< minAttempts=3) so it is treated as neutral, not down-ranked,
        // even though both observed calls failed.
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 2, 2),
                new ModelHealth(HEALTHY, 10, 0));

        List<ModelRef> ordered = router().reorder(List.of(SAGGING, HEALTHY), health);

        // Both score the neutral prior (SAGGING below the floor, HEALTHY perfect) → declared order holds.
        assertEquals(List.of(SAGGING, HEALTHY), ordered);
    }

    /** Tie on score → the declared (index) order is the stable tiebreak. */
    @Test
    void tieBreaksOnDeclaredOrder() {
        // Both perfectly healthy → equal score → declared order preserved.
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 0),
                new ModelHealth(HEALTHY, 10, 0));

        assertEquals(List.of(SAGGING, HEALTHY), router().reorder(List.of(SAGGING, HEALTHY), health));
        assertEquals(List.of(HEALTHY, SAGGING), router().reorder(List.of(HEALTHY, SAGGING), health));
    }

    /** weight=0 → CAPR ignored; the declared order always wins (the neutral default). */
    @Test
    void zeroWeightIgnoresCaprAndKeepsDeclaredOrder() {
        CaprRouter neutral = new CaprRouter(true, 0.0, 3);
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 10),
                new ModelHealth(HEALTHY, 10, 0));

        assertEquals(List.of(SAGGING, HEALTHY), neutral.reorder(List.of(SAGGING, HEALTHY), health));
    }

    /** A disabled router is a pass-through even with damning health data. */
    @Test
    void disabledRouterIsPassThrough() {
        CaprRouter off = new CaprRouter(false, 0.7, 3);
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 10),
                new ModelHealth(HEALTHY, 10, 0));

        assertEquals(List.of(SAGGING, HEALTHY), off.reorder(List.of(SAGGING, HEALTHY), health));
    }

    /** Single-candidate (the cron/replay/worker single-link path) is returned unchanged. */
    @Test
    void singleCandidateUnchanged() {
        assertEquals(List.of(HEALTHY), router().reorder(List.of(HEALTHY), Map.of()));
    }

    /** The result is over exactly the declared elements — never invents or drops a model. */
    @Test
    void resultIsAPermutationOfTheDeclaredChain() {
        List<ModelRef> declared = List.of(SAGGING, MIDDLE, HEALTHY);
        Map<ModelRef, ModelHealth> health = health(
                new ModelHealth(SAGGING, 10, 9),
                new ModelHealth(MIDDLE, 10, 5),
                new ModelHealth(HEALTHY, 10, 1));

        List<ModelRef> ordered = router().reorder(declared, health);

        assertEquals(declared.size(), ordered.size());
        assertEquals(Set.copyOf(declared), Set.copyOf(ordered));
    }
}
