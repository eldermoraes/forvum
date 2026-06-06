package ai.forvum.engine.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Writes one {@code capr_events} row per completed turn (ULTRAPLAN section 3.6 / 5.5, M18). The row is
 * the per-turn CAPR verdict keyed to the assistant message ({@code turnId}). v0.1 ships with judge mode
 * off, so a completed turn is recorded as passed with a {@code none} judge model; the actual judge-model
 * scoring (and the {@code /q/dashboard/capr} endpoint, e2e X10) is a later milestone. Separate from
 * {@code AgentMemory} so the turn's conversational tier and its observability verdict stay decoupled.
 */
@ApplicationScoped
public class CaprRecorder {

    private static final String JUDGE_DISABLED = "none";

    /** Record a passed verdict for {@code turnId} (judge mode off in v0.1). */
    @Transactional
    public void recordPassed(String sessionId, String agentId, long turnId) {
        CaprEventEntity event = new CaprEventEntity();
        event.sessionId = sessionId;
        event.agentId = agentId;
        event.turnId = turnId;
        event.passed = 1;
        event.judgeModel = JUDGE_DISABLED;
        event.rationale = "judge mode disabled (v0.1)";
        event.createdAt = System.currentTimeMillis();
        event.persist();
    }
}
