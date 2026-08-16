package ai.forvum.engine.routing;

import ai.forvum.core.ModelRef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a rolling per-model health snapshot from the {@code provider_calls} ledger for an agent (P3-4
 * #52). The genuinely per-model signal lives here: each {@code provider_calls} row carries
 * {@code (provider, model, error)} — a null {@code error} is a successful call, a non-null one a
 * model-level failure recorded by the M8 {@code FallbackChatModel} per attempt. Over the most recent
 * {@code window} rows for each {@code (provider, model)} pair (scoped to {@code agentId}), it tallies
 * attempts and failures into a {@link ModelHealth} the {@link CaprRouter} blends into a routing order.
 *
 * <p><b>Both ledgers feed the tally (#195).</b> {@code provider_calls} is the call-health signal (did the
 * model answer at all); {@code capr_events} rows carrying a non-null {@code model} column are genuine
 * per-turn judge verdicts written by {@code TurnJudge} — the answer-quality signal ({@code passed=0} is a
 * failed verdict). Each model's snapshot merges the most recent {@code window} rows of each, so routing
 * demotes a model whose replies FAIL the judge even when its calls succeed. Placeholder rows (judge
 * disabled/unavailable — {@code model IS NULL}) and archived rows are never tallied.
 */
@ApplicationScoped
public class ModelHealthReader {

    @Inject
    EntityManager em;

    /** The rolling window: the most recent N provider calls per {@code (provider, model)} pair. */
    @ConfigProperty(name = "forvum.routing.capr.window", defaultValue = "20")
    int window;

    /**
     * The rolling health of each {@code (provider, model)} in {@code candidates} for {@code agentId},
     * over the most recent {@link #window} calls per pair. A candidate with no recorded call is absent
     * from the map (the router treats absence as the neutral prior). SELECT-only at the transaction
     * boundary; it never writes. The caller ({@code LlmSelector.route}) degrades to the declared order on
     * any read failure, so a routing read never fails the turn.
     */
    @Transactional
    public Map<ModelRef, ModelHealth> health(String agentId, List<ModelRef> candidates) {
        Map<ModelRef, ModelHealth> result = new HashMap<>();
        for (ModelRef ref : candidates) {
            ModelHealth h = healthFor(agentId, ref);
            if (h != null) {
                result.put(ref, h);
            }
        }
        return result;
    }

    /**
     * Tally the most recent {@link #window} {@code provider_calls} rows (call health) plus the most
     * recent {@link #window} judged {@code capr_events} verdicts (#195, answer quality) for one
     * {@code (provider, model)} under {@code agentId}, newest first. Returns {@code null} when the model
     * has no recorded call and no judged verdict.
     */
    private ModelHealth healthFor(String agentId, ModelRef ref) {
        @SuppressWarnings("unchecked")
        List<Object> errors = em.createNativeQuery(
                "select error from provider_calls "
              + "where agent_id = :agentId and provider = :provider and model = :model "
              + "order by id desc limit :window")
                .setParameter("agentId", agentId)
                .setParameter("provider", ref.provider())
                .setParameter("model", ref.model())
                .setParameter("window", window)
                .getResultList();
        // Only genuinely judged verdicts carry a non-null model column; placeholder rows never tally.
        @SuppressWarnings("unchecked")
        List<Object> verdicts = em.createNativeQuery(
                "select passed from capr_events "
              + "where agent_id = :agentId and model = :model and is_archived = 0 "
              + "order by id desc limit :window")
                .setParameter("agentId", agentId)
                .setParameter("model", ref.toString())
                .setParameter("window", window)
                .getResultList();
        if (errors.isEmpty() && verdicts.isEmpty()) {
            return null;
        }
        int attempts = errors.size() + verdicts.size();
        int failures = 0;
        for (Object error : errors) {
            if (error != null) {
                failures++;
            }
        }
        for (Object passed : verdicts) {
            if (((Number) passed).intValue() == 0) {
                failures++;
            }
        }
        return new ModelHealth(ref, attempts, failures);
    }
}
