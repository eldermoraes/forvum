package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.BlockType;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.MessageEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The issue #190 stated verification at the integration level: two turns of ONE session through the
 * real {@code TurnService.dispatch -> SupervisorGraph} path. Turn 1's scripted model calls
 * {@code update_plan} and answers — the plan row must be persisted ({@code role='tool'},
 * {@code block_type='plan'}). Turn 2's model CAPTURES its {@code ChatRequest.messages()} — the framed
 * live plan must be fed back in the window, positioned before the user's question. Assertions are
 * scoped to this test's session ([M7] shared-{@code @TestProfile} DB rule).
 */
@QuarkusTest
@TestProfile(AgentPlanTurnIT.PlanTurnHomeProfile.class)
class AgentPlanTurnIT {

    @Inject
    TurnService turns;

    @BeforeEach
    void clearCaptures() {
        ScriptedPlanModelProvider.SEEN.clear();
    }

    @Test
    void thePlanRecordedOnTurnOneIsFedBackFramedOnTurnTwo() {
        // Turn 1: the model calls update_plan, the engine persists the plan, the turn completes.
        turns.dispatch(new ChannelMessage("web", "plan-e2e", "make a plan for the report", Instant.now()),
                e -> { });

        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> planRows = MessageEntity.list(
                    "sessionId = ?1 and blockType = ?2", "web:plan-e2e", BlockType.PLAN.dbValue());
            assertEquals(1, planRows.size(), "turn 1 persisted exactly one plan row");
            assertEquals(Role.TOOL.dbValue(), planRows.get(0).role);
            assertTrue(planRows.get(0).content.contains("[>] gather the inputs"),
                    "the row carries the rendered checklist: " + planRows.get(0).content);
        });

        // Turn 2, same session: the CAPTURED request must contain the framed live plan.
        turns.dispatch(new ChannelMessage("web", "plan-e2e", "continue where you left off", Instant.now()),
                e -> { });

        List<ChatMessage> turnTwo = ScriptedPlanModelProvider.SEEN
                .get(ScriptedPlanModelProvider.SEEN.size() - 1);
        int planIndex = -1;
        int questionIndex = -1;
        for (int i = 0; i < turnTwo.size(); i++) {
            if (turnTwo.get(i) instanceof UserMessage user) {
                if (user.singleText().contains("<current_plan>")) {
                    planIndex = i;
                }
                if (user.singleText().contains("continue where you left off")) {
                    questionIndex = i;
                }
            }
        }
        assertTrue(planIndex >= 0,
                "turn 2's captured ChatRequest.messages() must carry the framed live plan: " + turnTwo);
        assertTrue(((UserMessage) turnTwo.get(planIndex)).singleText().contains("[>] gather the inputs"),
                "the framed block carries the recorded plan content");
        assertTrue(questionIndex > planIndex,
                "the plan block is injected BEFORE the user's question (context -> question)");
    }

    /** Seeds an agent {@code main} pinned to the capturing scripted-plan model, in a throwaway home. */
    public static class PlanTurnHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-plan-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"), "{ \"primaryModel\": \"scriptedplan:m\" }");
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
