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
 * <p><b>Why {@code provider_calls} and not {@code capr_events}.</b> The {@code capr_events} table records
 * a per-turn verdict keyed to {@code agent_id} + {@code turn_id} (the assistant {@code messages.id}) but
 * carries NO model column, and in v0.1 every row is a placeholder {@code passed=1} / {@code judge_model
 * ="none"} — it cannot attribute a pass/fail to a specific model. {@code provider_calls} is per-model,
 * already populated by the live fallback path, and recency-orderable, so it is the operative "pass rate
 * per model" signal until a real judge model lands and a model column is added to {@code capr_events}
 * (a documented fast-follow — see ULTRAPLAN §7.3 item 4).
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
     * Tally the most recent {@link #window} {@code provider_calls} rows for one {@code (provider, model)}
     * under {@code agentId}, newest first. Returns {@code null} when the model has no recorded call.
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
        if (errors.isEmpty()) {
            return null;
        }
        int attempts = errors.size();
        int failures = 0;
        for (Object error : errors) {
            if (error != null) {
                failures++;
            }
        }
        return new ModelHealth(ref, attempts, failures);
    }
}
