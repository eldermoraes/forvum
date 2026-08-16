package ai.forvum.engine.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link PlanStore} test double (#190): append-only newest-wins over a plain list, exposing
 * every save so tests assert the write path. No {@code @Vetoed} needed — the seam is an interface, so
 * this never enters CDI discovery from a test source root as an alternative bean type.
 */
public final class InMemoryPlanStore implements PlanStore {

    /** One recorded save, in call order. */
    public record SavedPlan(String sessionId, String agentId, String rendered) {
    }

    public final List<SavedPlan> saved = new ArrayList<>();

    @Override
    public void save(String sessionId, String agentId, String renderedPlan) {
        saved.add(new SavedPlan(sessionId, agentId, renderedPlan));
    }

    @Override
    public Optional<String> latest(String sessionId, String agentId) {
        for (int i = saved.size() - 1; i >= 0; i--) {
            SavedPlan plan = saved.get(i);
            if (plan.sessionId().equals(sessionId) && plan.agentId().equals(agentId)) {
                return Optional.of(plan.rendered());
            }
        }
        return Optional.empty();
    }
}
