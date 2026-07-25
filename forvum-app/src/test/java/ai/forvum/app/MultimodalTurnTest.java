package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.agent.TurnService;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The #185 multimodal turn end-to-end on the assembled app classpath: a scripted orchestrator model emits an
 * {@code image.analyze} tool call, the engine's {@code ToolExecutor} runs the {@code forvum-tools-multimodal}
 * provider (belt + {@code MEDIA_ANALYZE} permitted), which reads the workspace image and drives the
 * engine-backed {@code MediaAnalysis} sub-generation — resolved (via the {@code tools/multimodal.json} model
 * override) to a capturing vision model. Proves the exact image bytes reach the sub-generation as
 * {@link ImageContent}, the {@code tool_invocations} row is {@code ok}, and the turn completes with a terminal
 * {@code Done}. The belt gate for the same call is proven by {@link MultimodalBeltDeniedTest}.
 */
@QuarkusTest
@TestProfile(MultimodalTurnTest.MultimodalHomeProfile.class)
class MultimodalTurnTest {

    static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};

    @Inject
    TurnService turns;

    @Inject
    FakeVisionModelProvider vision;

    @Test
    void anImageAnalyzeTurnReachesTheVisionModelWithTheExactBytesAndIsAudited() {
        List<AgentEvent> events = new ArrayList<>();
        turns.dispatch(new ChannelMessage("web", "sess-mm", "analyze a.png", Instant.now()), events::add);

        assertTrue(events.stream().anyMatch(Done.class::isInstance), "the turn completes with a terminal Done");
        assertFalse(events.stream().anyMatch(ErrorEvent.class::isInstance), "the turn does not error");

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-mm", "ok", "image.analyze"),
                "image.analyze ran and was audited ok");

        // The sub-generation reached the vision model with the exact workspace file bytes as ImageContent.
        String expectedBase64 = Base64.getEncoder().encodeToString(PNG_BYTES);
        assertTrue(vision.capturedRequests().stream().anyMatch(r -> hasImage(r, expectedBase64)),
                "the vision sub-generation received the exact image bytes as base64 ImageContent");
    }

    private static boolean hasImage(ChatRequest request, String expectedBase64) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .flatMap(m -> m.contents().stream())
                .filter(ImageContent.class::isInstance).map(ImageContent.class::cast)
                .anyMatch(ic -> expectedBase64.equals(ic.image().base64Data()));
    }

    /** Seeds an agent with {@code image.analyze} in its belt (+ a restricted no-belt agent), a default
     * identity (default-user grants MEDIA_ANALYZE), a workspace image, and a multimodal config pinning the
     * vision sub-generation to the capturing {@code vision-capture} provider. */
    public static class MultimodalHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-multimodal-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-mm:m\", \"allowedTools\": [\"image.analyze\"], "
                      + "\"identityId\": \"default\" }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("default.json"), "{ \"displayName\": \"Default\" }");

                Path tools = Files.createDirectories(home.resolve("tools"));
                Files.writeString(tools.resolve("multimodal.json"), "{ \"model\": \"vision-capture:vision\" }");

                Path workspace = Files.createDirectories(home.resolve("workspace"));
                Files.write(workspace.resolve("a.png"), PNG_BYTES);
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
