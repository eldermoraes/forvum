package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.engine.agent.TurnService;
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
 * The #185 belt-gate red-check: an agent whose belt does NOT contain {@code image.analyze} denies the
 * scripted call and never reaches the vision sub-generation (the companion to {@link MultimodalTurnTest}'s
 * success path — separate class because a turn always routes to the single {@code main} agent, so the
 * belt-present and belt-absent cases need distinct homes). Non-live; runs in the default build.
 */
@QuarkusTest
@TestProfile(MultimodalBeltDeniedTest.EmptyBeltHomeProfile.class)
class MultimodalBeltDeniedTest {

    @Inject
    TurnService turns;

    @Inject
    FakeVisionModelProvider vision;

    @Test
    void anImageAnalyzeOutsideTheBeltIsDeniedAuditedAndNeverReachesTheVisionModel() {
        int before = vision.capturedRequests().size();
        List<AgentEvent> events = new ArrayList<>();
        turns.dispatch(new ChannelMessage("web", "denied-mm", "analyze a.png", Instant.now()), events::add);

        // The turn still completes (a denied tool result goes back to the model, which answers "done").
        assertTrue(events.stream().anyMatch(Done.class::isInstance), "the turn completes despite the denial");
        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:denied-mm", "denied", "image.analyze"),
                "an out-of-belt image.analyze is denied + audited");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:denied-mm", "ok", "image.analyze"),
                "the denied tool never executed");
        assertEquals(before, vision.capturedRequests().size(),
                "a denied image.analyze never reaches the vision sub-generation");
    }

    /** Seeds a {@code main} agent with an EMPTY belt, pinned to the scripted orchestrator, plus the workspace
     * image + a vision-pinned multimodal config (which must go unused because the call never executes). */
    public static class EmptyBeltHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-multimodal-denied-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-mm:m\", \"allowedTools\": [] }");

                Path tools = Files.createDirectories(home.resolve("tools"));
                Files.writeString(tools.resolve("multimodal.json"), "{ \"model\": \"vision-capture:vision\" }");

                Path workspace = Files.createDirectories(home.resolve("workspace"));
                Files.write(workspace.resolve("a.png"), MultimodalTurnTest.PNG_BYTES);
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.workspace.root", HOME.resolve("workspace").toString());
        }
    }
}
