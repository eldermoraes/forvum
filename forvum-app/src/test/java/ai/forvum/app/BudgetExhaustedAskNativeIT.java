package ai.forvum.app;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native parity for the #169 budget hard stop: re-runs {@link BudgetExhaustedAskTest} against the
 * produced native binary, OUT-OF-PROCESS. The Decision-8 pre-call gate fires before any provider HTTP
 * call — the turn is deterministic and offline — so unlike {@code OllamaNativeTurnIT} this carries NO
 * {@code @Tag("live")} and runs in the DEFAULT native leg: a free, real native exercise of the
 * costBudget parse, the {@code BudgetMeter} SQL aggregation, the pre-call gate, and the
 * {@code budget_exhausted} error surface (the complete enforcement path in the image).
 *
 * <p>The seeded zero-budget home reaches the launched binary as {@code -Dforvum.home} via the inherited
 * {@link BudgetExhaustedAskTest.ZeroBudgetHomeProfile} config overrides; the {@code @TestProfile} is
 * re-declared here so the native subprocess gets it without relying on annotation inheritance.
 */
@QuarkusMainIntegrationTest
@TestProfile(BudgetExhaustedAskTest.ZeroBudgetHomeProfile.class)
class BudgetExhaustedAskNativeIT extends BudgetExhaustedAskTest {
}
