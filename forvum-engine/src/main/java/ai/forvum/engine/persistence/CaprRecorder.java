package ai.forvum.engine.persistence;

import ai.forvum.engine.capr.TurnVerdict;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Writes one {@code capr_events} row per completed turn (ULTRAPLAN section 3.6 / 5.5, M18). The row is
 * the per-turn CAPR verdict keyed to the assistant message ({@code turnId}). With the judge disabled
 * (the default — {@code forvum.capr.judge.enabled=false}) a completed turn is recorded as passed with a
 * {@code none} judge model and no model attribution; with it enabled, {@code TurnJudge} (#195) writes a
 * genuine pass/fail + score verdict attributed to the answering model via {@link #recordVerdict}.
 * Separate from {@code AgentMemory} so the turn's conversational tier and its observability verdict stay
 * decoupled.
 */
@ApplicationScoped
public class CaprRecorder {

    private static final String JUDGE_DISABLED = "none";

    @Inject
    EntityManager em;

    /** Record a neutral passed verdict for {@code turnId} (judge mode off — the default). */
    @Transactional
    public void recordPassed(String sessionId, String agentId, long turnId) {
        CaprEventEntity event = baseEvent(sessionId, agentId, turnId);
        event.passed = 1;
        event.judgeModel = JUDGE_DISABLED;
        event.rationale = "judge mode disabled";
        event.persist();
    }

    /**
     * Record a genuine judged verdict for {@code turnId} (#195): pass/fail + normalized score from
     * {@code verdict}, attributed to {@code model} (the answering model's canonical
     * {@code provider:model} form, or {@code null} when the ledger held no successful call to attribute).
     */
    @Transactional
    public void recordVerdict(String sessionId, String agentId, long turnId, String model,
            String judgeModel, TurnVerdict verdict) {
        CaprEventEntity event = baseEvent(sessionId, agentId, turnId);
        event.passed = verdict.passed() ? 1 : 0;
        event.score = verdict.score();
        event.model = model;
        event.judgeModel = judgeModel;
        event.rationale = verdict.reason();
        event.persist();
    }

    /**
     * Record the judge-unavailable fallback for {@code turnId} (#195): the neutral passed shape of the
     * disabled path (no model attribution, no score — a judge outage never punishes the answering
     * model's health), with a {@code FailureClass}-compatible token in the rationale.
     */
    @Transactional
    public void recordJudgeUnavailable(String sessionId, String agentId, long turnId,
            String failureClass, String detail) {
        CaprEventEntity event = baseEvent(sessionId, agentId, turnId);
        event.passed = 1;
        event.judgeModel = JUDGE_DISABLED;
        event.rationale = "judge unavailable (" + failureClass + "): " + detail;
        event.persist();
    }

    /**
     * The model that answered the most recent successful call of {@code sessionId}/{@code agentId}, in
     * the canonical {@code provider:model} form, or {@code null} when the ledger holds none. Read by
     * {@code TurnJudge} BEFORE its own judge chat appends to the same ledger.
     */
    @Transactional
    public String lastAnsweringModel(String sessionId, String agentId) {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = em.createNativeQuery(
                "select provider, model from provider_calls "
              + "where session_id = :sessionId and agent_id = :agentId and error is null "
              + "order by id desc limit 1")
                .setParameter("sessionId", sessionId)
                .setParameter("agentId", agentId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        return row[0] + ":" + row[1];
    }

    private static CaprEventEntity baseEvent(String sessionId, String agentId, long turnId) {
        CaprEventEntity event = new CaprEventEntity();
        event.sessionId = sessionId;
        event.agentId = agentId;
        event.turnId = turnId;
        event.createdAt = System.currentTimeMillis();
        return event;
    }
}
