package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.core.budget.ExhaustionCause;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link TurnToolBudget} grant semantics, including the atomic-reservation concurrency contract (#169). */
class TurnToolBudgetTest {

    @Test
    void grantsExactlyCapConsumptionsThenThrowsToolCapHit() {
        UUID turnId = UUID.randomUUID();
        TurnToolBudget budget = new TurnToolBudget(2, turnId);

        budget.consumeOne();
        budget.consumeOne();
        BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class, budget::consumeOne);

        assertEquals(ExhaustionCause.TOOL_CAP_HIT, e.cause());
        assertEquals(turnId, e.turnId());
    }

    @Test
    void aZeroCapDeniesTheVeryFirstConsumption() {
        TurnToolBudget budget = new TurnToolBudget(0, null);

        assertThrows(BudgetExhaustedException.class, budget::consumeOne,
                "toolBudget: 0 is the always-deny boundary — no tool may run");
    }

    @Test
    void negativeCapsAreAWiringBugAndRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TurnToolBudget(-1, null));
    }

    @Test
    void concurrentConsumersNeverOverConsumeTheCap() throws Exception {
        // The #169 atomic-reservation contract: across ANY interleaving of concurrent consumers,
        // exactly `cap` grants succeed — a shared budget can never be overspent by a race.
        final int cap = 7;
        final int threads = 32;
        TurnToolBudget budget = new TurnToolBudget(cap, null);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    start.await();
                    try {
                        budget.consumeOne();
                        granted.incrementAndGet();
                    } catch (BudgetExhaustedException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } // try-with-resources: close() awaits every submitted task

        assertEquals(cap, granted.get(), "exactly `cap` grants must succeed");
        assertEquals(threads - cap, refused.get(), "every other consumer must be refused");
    }
}
