package ai.forvum.engine.persistence;

import ai.forvum.core.budget.BudgetMeter;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.budget.ExhaustionCause;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.Spend;
import ai.forvum.core.budget.Usage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Default {@link BudgetMeter} backed by the {@code provider_calls} ledger (ULTRAPLAN section 4.3.5.2).
 *
 * <p>A single aggregation trip computes spent USD and tokens over the budget's {@link
 * ai.forvum.core.budget.Window}; rows with a {@code null cost_usd} contribute zero. A dimension whose
 * matching cap on {@link CostBudget} is {@code null} is opted out — its {@code spent}/{@code remaining}
 * are reported as {@code null} and it never triggers exhaustion. No {@code synchronized} (ULTRAPLAN
 * section 3.8); the read runs on the calling virtual thread inside a transaction.
 */
@Singleton
public class PanacheBudgetMeter implements BudgetMeter {

    private final EntityManager em;

    @Inject
    public PanacheBudgetMeter(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public Usage usage(CostBudget budget) {
        boolean usdActive = budget.maxUsd() != null;
        boolean tokActive = budget.maxTokens() != null;

        StringBuilder jpql = new StringBuilder(
                "select coalesce(sum(p.costUsd), 0), coalesce(sum(p.tokensIn + p.tokensOut), 0) "
              + "from ProviderCallEntity p where ");

        Long startMillis = null;
        String sessionId = null;
        String agentId = null;
        switch (budget.window()) {
            case DayWindow d -> {
                startMillis = LocalDate.now(d.tz()).atStartOfDay(d.tz()).toInstant().toEpochMilli();
                jpql.append("p.createdAt >= :start");
            }
            case SessionWindow s -> {
                sessionId = s.sessionId();
                agentId = s.agentId();
                jpql.append("p.sessionId = :sid and p.agentId = :aid");
            }
        }

        var query = em.createQuery(jpql.toString(), Object[].class);
        if (startMillis != null) {
            query.setParameter("start", startMillis);
        }
        if (sessionId != null) {
            query.setParameter("sid", sessionId);
            query.setParameter("aid", agentId);
        }
        Object[] row = query.getSingleResult();
        double sumUsd = ((Number) row[0]).doubleValue();
        long sumTokens = ((Number) row[1]).longValue();

        // cost_usd is a REAL column summed in double, so an exact-cap total (e.g. ten 0.10 calls vs a
        // 1.00 cap) can land one ULP short (0.9999999999999999). Round to micro-USD before the cap test
        // so exact-cap sums classify as exhausted, without disturbing real cent/sub-cent values. (When a
        // cost producer lands — providers currently write cost_usd=null — sum in BigDecimal end-to-end.)
        BigDecimal spentUsd = usdActive
                ? BigDecimal.valueOf(sumUsd).setScale(6, RoundingMode.HALF_UP) : null;
        Long spentTokens = tokActive ? sumTokens : null;

        boolean usdExhausted = usdActive && spentUsd.compareTo(budget.maxUsd()) >= 0;
        boolean tokExhausted = tokActive && spentTokens >= budget.maxTokens();

        BigDecimal remainingUsd = usdActive
                ? budget.maxUsd().subtract(spentUsd).max(BigDecimal.ZERO) : null;
        Long remainingTokens = tokActive
                ? Math.max(0L, budget.maxTokens() - spentTokens) : null;

        boolean exhausted = usdExhausted || tokExhausted;
        ExhaustionCause cause = (usdExhausted && tokExhausted) ? ExhaustionCause.BOTH_CAPS_HIT
                : usdExhausted ? ExhaustionCause.USD_CAP_HIT
                : tokExhausted ? ExhaustionCause.TOKEN_CAP_HIT
                : null;

        return new Usage(new Spend(spentUsd, spentTokens),
                new Spend(remainingUsd, remainingTokens), exhausted, cause);
    }
}
