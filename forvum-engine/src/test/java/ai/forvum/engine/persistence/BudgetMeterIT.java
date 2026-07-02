package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.budget.ExhaustionCause;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.Usage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneOffset;

/**
 * Integration test for {@link PanacheBudgetMeter} over the {@code provider_calls} ledger. Each test
 * runs in its own transaction, clears the ledger, inserts calls, and asserts the {@link Usage}
 * snapshot. Surefire-run (headless library).
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class BudgetMeterIT {

    @Inject
    PanacheBudgetMeter meter;

    private static final DayWindow TODAY = new DayWindow(ZoneOffset.UTC);

    /** Persist one provider_calls row (no @Transactional — runs inside the test's transaction). */
    private void call(String sessionId, String agentId, Double usd, long tokensIn, long tokensOut) {
        ProviderCallEntity p = new ProviderCallEntity();
        p.sessionId = sessionId;
        p.agentId = agentId;
        p.provider = "ollama";
        p.model = "m";
        p.tokensIn = tokensIn;
        p.tokensOut = tokensOut;
        p.costUsd = usd;
        p.latencyMs = 1;
        p.isFallback = 0;
        p.createdAt = System.currentTimeMillis();
        p.persist();
    }

    @Test
    @Transactional
    void usdOnlyBudgetNotExhausted() {
        ProviderCallEntity.deleteAll();
        call("s", "a", 0.30, 10, 10);
        call("s", "a", 0.20, 10, 10);

        Usage u = meter.usage(new CostBudget(new BigDecimal("1.00"), null, TODAY));

        assertEquals(0, new BigDecimal("0.50").compareTo(u.spent().usd()));
        assertNull(u.spent().tokens(), "tokens dimension is opted out when maxTokens is null");
        assertEquals(0, new BigDecimal("0.50").compareTo(u.remaining().usd()));
        assertFalse(u.exhausted());
        assertNull(u.cause());
    }

    @Test
    @Transactional
    void usdOnlyBudgetExhausted() {
        ProviderCallEntity.deleteAll();
        call("s", "a", 0.60, 0, 0);
        call("s", "a", 0.50, 0, 0);

        Usage u = meter.usage(new CostBudget(new BigDecimal("1.00"), null, TODAY));

        assertTrue(u.exhausted());
        assertEquals(ExhaustionCause.USD_CAP_HIT, u.cause());
        assertEquals(0, BigDecimal.ZERO.compareTo(u.remaining().usd()));
    }

    @Test
    @Transactional
    void tokenOnlyBudget() {
        ProviderCallEntity.deleteAll();
        call("s", "a", null, 40, 40); // 80 tokens
        call("s", "a", null, 10, 20); // 30 tokens -> 110 total

        Usage u = meter.usage(new CostBudget(null, 100L, TODAY));

        assertEquals(110L, u.spent().tokens());
        assertNull(u.spent().usd());
        assertTrue(u.exhausted());
        assertEquals(ExhaustionCause.TOKEN_CAP_HIT, u.cause());
    }

    @Test
    @Transactional
    void bothCapsHit() {
        ProviderCallEntity.deleteAll();
        call("s", "a", 1.50, 60, 60); // 1.50 usd, 120 tokens

        Usage u = meter.usage(new CostBudget(new BigDecimal("1.00"), 100L, TODAY));

        assertTrue(u.exhausted());
        assertEquals(ExhaustionCause.BOTH_CAPS_HIT, u.cause());
    }

    @Test
    @Transactional
    void nullCostContributesZero() {
        ProviderCallEntity.deleteAll();
        call("s", "a", null, 5, 5);

        Usage u = meter.usage(new CostBudget(new BigDecimal("1.00"), null, TODAY));

        assertEquals(0, BigDecimal.ZERO.compareTo(u.spent().usd()));
        assertFalse(u.exhausted());
    }

    @Test
    @Transactional
    void sessionWindowFiltersBySessionAndAgent() {
        ProviderCallEntity.deleteAll();
        call("s1", "a1", 0.40, 0, 0); // counted
        call("s1", "a2", 9.00, 0, 0); // same session, different agent -> excluded by the agentId predicate
        call("s2", "a1", 9.00, 0, 0); // same agent, different session -> excluded by the sessionId predicate

        Usage u = meter.usage(new CostBudget(new BigDecimal("1.00"), null, new SessionWindow("s1", "a1")));

        assertEquals(0, new BigDecimal("0.40").compareTo(u.spent().usd()),
                "only the (s1,a1) row counts — both predicates of the conjunction must apply");
        assertFalse(u.exhausted());
    }

    @Test
    @Transactional
    void dayWindowScopedToAnAgentCountsOnlyThatAgentsSpend() {
        // #169 / Decision 10: spend is tracked independently per agent, so the per-agent read must not
        // let one agent's day spend exhaust another's budget.
        ProviderCallEntity.deleteAll();
        call("s", "worker-1", null, 30, 30); // 60 tokens for worker-1
        call("s", "other", null, 500, 500);  // another agent's day spend — must not count

        Usage scoped = meter.usage(new CostBudget(null, 100L, TODAY), "worker-1");

        assertEquals(60L, scoped.spent().tokens(), "only worker-1's rows aggregate");
        assertFalse(scoped.exhausted(), "the other agent's 1000 tokens must not exhaust worker-1's budget");
    }

    @Test
    @Transactional
    void dayWindowWithoutAnAgentKeepsTheUnscopedRead() {
        ProviderCallEntity.deleteAll();
        call("s", "a1", null, 30, 30);
        call("s", "a2", null, 40, 40);

        Usage unscoped = meter.usage(new CostBudget(null, 100L, TODAY), null);

        assertEquals(140L, unscoped.spent().tokens(), "a null agent id aggregates every row in the day");
        assertTrue(unscoped.exhausted());
    }

    @Test
    @Transactional
    void sessionWindowIgnoresTheCallingAgentParameter() {
        // A SessionWindow already carries its own (sessionId, agentId) pair — the per-agent parameter
        // must not narrow (or widen) it further.
        ProviderCallEntity.deleteAll();
        call("s1", "a1", null, 50, 50);

        Usage u = meter.usage(new CostBudget(null, 60L, new SessionWindow("s1", "a1")), "someone-else");

        assertEquals(100L, u.spent().tokens(), "the window's own pair governs, not the parameter");
        assertTrue(u.exhausted());
    }
}
