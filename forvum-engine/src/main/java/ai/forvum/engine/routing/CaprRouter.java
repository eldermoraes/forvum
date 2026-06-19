package ai.forvum.engine.routing;

import ai.forvum.core.ModelRef;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The deterministic, CAPR-driven adaptive router (P3-4 #52, ULTRAPLAN §7.3 item 4). Given a declared
 * candidate order ({@link ai.forvum.core.FallbackChain#links()}) and a rolling per-model health snapshot
 * ({@link ModelHealth}, derived from {@code provider_calls}), it computes a blended score per model and
 * <em>reorders</em> the candidates so a model with a sagging recent pass rate sinks below a healthier
 * sibling. It serves the Context-Engineering <b>Select</b> pillar (model routing).
 *
 * <p><b>Authority set (DR-4c [DP-8]).</b> The router reorders the declared chain only: it keeps every
 * declared link (it never drops or invents a model in v0.5 — dropping is a documented deferral), and the
 * declared order is the deterministic tiebreak, so the operator's {@code primary} preference wins ties.
 *
 * <p><b>Blended score.</b> Per candidate {@code m} with health {@code (attempts, failures)}:
 * <pre>{@code score(m) = (1 - weight) * NEUTRAL_PRIOR + weight * passRate(m)}</pre>
 * where {@code passRate = (attempts - failures) / attempts}, {@code NEUTRAL_PRIOR = 1.0}, and
 * {@code weight ∈ [0, 1]} (the recency-weighted blend toward the neutral prior). A model with fewer than
 * {@code minAttempts} observed calls is treated as the neutral prior ({@code score = 1.0}), so a
 * cold-start model is never starved of its declared position. {@code weight = 0} (or no data at all)
 * makes every score the neutral prior → the declared order is returned unchanged (the feature is
 * neutral by default for the common case). The sort is STABLE on descending score with the declared
 * index as the tiebreak, so equal-score candidates keep their declared order.
 *
 * <p>Deterministic and native-clean: no LLM call on the hot path (the §7.3 "router is a small local
 * model" framing is a documented v1.0 aspiration, not built here), no reflection, pure arithmetic.
 */
@ApplicationScoped
public class CaprRouter {

    private static final double NEUTRAL_PRIOR = 1.0;

    /**
     * Master switch. When {@code false} the router is a pass-through (returns the declared order verbatim
     * and reads no health). Default {@code true} — but with no recorded calls the blend already returns
     * the declared order, so enabling it is a no-op until a model actually sags.
     */
    @ConfigProperty(name = "forvum.routing.capr.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * The blend weight {@code ∈ [0, 1]} toward the recent pass rate. {@code 0.0} = ignore CAPR (declared
     * order always wins); {@code 1.0} = score is the raw pass rate. Out-of-range values are clamped.
     */
    @ConfigProperty(name = "forvum.routing.capr.weight", defaultValue = "0.7")
    double weight;

    /**
     * The minimum observed attempts before a model's pass rate counts. Below it the model scores the
     * neutral prior (cold-start protection): a model must demonstrate enough recent calls to be down-ranked.
     */
    @ConfigProperty(name = "forvum.routing.capr.min-attempts", defaultValue = "3")
    int minAttempts;

    /** No-arg CDI constructor. */
    public CaprRouter() {
    }

    /** Construct with explicit knobs — the unit-test seam (no CDI / no config). */
    public CaprRouter(boolean enabled, double weight, int minAttempts) {
        this.enabled = enabled;
        this.weight = weight;
        this.minAttempts = minAttempts;
    }

    /**
     * Reorder {@code candidates} (declared, primary-first) by blended health score, descending, with the
     * declared index as the stable tiebreak. {@code health} maps a {@link ModelRef} to its rolling
     * snapshot; a candidate absent from the map (no recorded calls) scores the neutral prior. Returns a
     * new immutable list over exactly the same elements — never empty, never with an invented model. A
     * single candidate or a disabled router returns the input order unchanged.
     */
    public List<ModelRef> reorder(List<ModelRef> candidates, Map<ModelRef, ModelHealth> health) {
        if (!enabled || candidates.size() < 2) {
            return List.copyOf(candidates);
        }
        double w = Math.max(0.0, Math.min(1.0, weight));
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new Scored(candidates.get(i), i, score(candidates.get(i), health, w)));
        }
        // Descending score; declared index is the deterministic tiebreak (stable on equal scores).
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            return byScore != 0 ? byScore : Integer.compare(a.index, b.index);
        });
        List<ModelRef> ordered = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            ordered.add(s.ref);
        }
        return List.copyOf(ordered);
    }

    private double score(ModelRef ref, Map<ModelRef, ModelHealth> health, double w) {
        ModelHealth h = health.get(ref);
        if (h == null || h.attempts() < minAttempts) {
            return NEUTRAL_PRIOR; // cold-start / no data → keep the declared position
        }
        return (1.0 - w) * NEUTRAL_PRIOR + w * h.passRate();
    }

    private record Scored(ModelRef ref, int index, double score) {
    }
}
