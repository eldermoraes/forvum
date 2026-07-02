package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.persistence.MessageEntity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The #169 cost-budget hard stop end-to-end through {@link TurnService#dispatch}: {@code main} declares
 * {@code costBudget.maxTokens = 8} while one fake-model turn ledgers exactly 10 tokens — so the FIRST
 * turn completes and every later turn must be stopped by the Decision-8 pre-call gate BEFORE any
 * provider call, surfacing a terminal {@code code = "budget_exhausted"} {@link ErrorEvent}. The
 * {@link TokenCountingModelProvider} invocation counter is the observation point: a blocked turn must
 * not increment it (the issue's "observe the hard stop before an extra model invocation"), and the
 * conversational tier must keep no rows for the blocked turn (persist-after-success, M7).
 * Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceCostBudgetIT.CostBudgetHomeProfile.class)
class TurnServiceCostBudgetIT {

    @Inject
    TurnService turns;

    @Test
    void aTurnOverTheTokenCapIsStoppedBeforeAnyProviderCall() {
        TokenCountingModelProvider.CALLS.set(0);
        List<AgentEvent> firstTurn = new ArrayList<>();
        List<AgentEvent> secondTurn = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "budget-user", "first question", Instant.now()),
                firstTurn::add);
        turns.dispatch(new ChannelMessage("web", "budget-user", "second question", Instant.now()),
                secondTurn::add);

        // Turn 1 fits the (not yet spent) budget: it runs the model once and completes.
        assertInstanceOf(Done.class, firstTurn.get(firstTurn.size() - 1),
                "the first turn has budget headroom and must complete");
        assertEquals(1, TokenCountingModelProvider.CALLS.get(), "turn 1 made exactly one model call");

        // Turn 2 finds 10 spent tokens >= the 8-token cap: hard-stopped BEFORE the provider.
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, secondTurn.get(secondTurn.size() - 1),
                "the second turn must be stopped by the budget gate");
        assertEquals("budget_exhausted", error.code());
        assertTrue(error.message().contains("TOKEN_CAP_HIT"),
                "the safe message names the exhausted cap, got: " + error.message());
        assertEquals(1, TokenCountingModelProvider.CALLS.get(),
                "the hard stop must fire BEFORE any additional provider call");
        assertEquals(0L, MessageEntity.count("sessionId = ?1 and content = ?2",
                        "web:budget-user", "second question"),
                "a blocked turn persists no conversational rows (persist-after-success)");
    }

    /** Seeds {@code main} with the token-counting model and an 8-token day budget (one turn = 10). */
    public static class CostBudgetHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-cost-budget-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"tokencounting:m\", "
                      + "\"costBudget\": { \"maxTokens\": 8 } }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
