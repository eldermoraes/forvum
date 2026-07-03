package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.persistence.ToolInvocationEntity;

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
 * The #169 tool-budget hard stop end-to-end through {@link TurnService#dispatch}: {@code main} declares
 * {@code toolBudget: 0} with {@code fs.write} IN its belt and a permissive resolved identity, so the
 * scripted model's tool call clears the belt AND the RBAC scope gates — only the fourth (budget) gate
 * denies it. The turn must abort with a terminal {@code code = "budget_exhausted"} {@link ErrorEvent}
 * (never render the exhaustion back to the model), audit the blocked attempt {@code denied}, and run
 * zero tools. Contrast with {@code TurnServiceRbacIT}'s permissive path (no budget → the same scripted
 * write RUNS): the only difference here is the declared cap, proving the budget — not belt or scope —
 * is what stops the call. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceToolBudgetIT.ToolBudgetHomeProfile.class)
class TurnServiceToolBudgetIT {

    @Inject
    TurnService turns;

    @Test
    void aZeroToolBudgetDeniesTheFirstToolCallAndAbortsTheTurn() {
        List<AgentEvent> events = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "sess-tb", "write something", Instant.now()),
                events::add);

        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(events.size() - 1),
                "an exhausted tool budget must abort the turn, not feed the model a result");
        assertEquals("budget_exhausted", error.code());
        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-tb", "denied", "fs.write"),
                "the over-budget attempt is audited denied");
        assertEquals(0L, ToolInvocationEntity.count("sessionId = ?1 and status = ?2",
                        "web:sess-tb", "ok"),
                "no tool ran — the budget gate stopped the very first execution");
    }

    /**
     * Seeds {@code main} with the scripted tool-calling model, {@code fs.write} in the belt, a permissive
     * {@code openweb} identity mapped to (web, sess-tb) — and {@code toolBudget: 0}, the only restrictive
     * knob in the profile.
     */
    public static class ToolBudgetHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-tool-budget-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"toolBudget\": 0 }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("openweb.json"),
                        "{ \"displayName\": \"Open\", \"channelAccounts\": { \"web\": \"sess-tb\" } }");
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
