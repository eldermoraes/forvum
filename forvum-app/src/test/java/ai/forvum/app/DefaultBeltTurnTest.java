package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.agent.TurnService;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The #184 default belt, wired end-to-end (D1 + D2). A fresh-scaffold-shaped {@code main} agent (the widened
 * belt + {@code identityId: "default"} + {@code identities/default.json}) drives one real turn through
 * {@code TurnService.dispatch → SupervisorGraph → ToolExecutor} against an in-process scripted provider that
 * requests {@code web.search}. It proves the belt is actually usable: the model IS offered the belt (the D2
 * identity wiring makes the turn resolve to {@code default}, not anonymous, so {@code scopeVisibleBelt} keeps
 * the tools), {@code web.search} EXECUTES and audits {@code ok}, and its result reaches the model's next
 * request. {@code tools/web.json} is seeded {@code {"backend":"brave"}} (no key) so the invoke is
 * config-shaped and OFFLINE — post-#192 a keyless invoke would dial DuckDuckGo from CI.
 */
@QuarkusTest
@TestProfile(DefaultBeltTurnTest.WiredHomeProfile.class)
class DefaultBeltTurnTest {

    @Inject
    TurnService turns;

    @Inject
    ScriptedWebSearchModelProvider model;

    @BeforeEach
    void resetCapture() {
        model.reset();
    }

    @Test
    void theDefaultBeltIsOfferedExecutedAndItsResultReachesTheModel() {
        List<AgentEvent> events = new ArrayList<>();
        ChannelMessage message = new ChannelMessage("web", "sess-belt", "search the web", Instant.now());

        turns.dispatch(message, events::add);

        // The turn completes normally.
        assertTrue(events.stream().anyMatch(Done.class::isInstance), "the turn must complete with a Done");
        assertTrue(events.stream().noneMatch(ErrorEvent.class::isInstance), "no ErrorEvent on the happy turn");

        // D2: the identity wiring made the turn resolve to 'default' (default-user, all scopes), so the model
        // WAS offered the belt on its first request — proving scopeVisibleBelt did not filter it to empty.
        List<String> offered = model.capturedRequests().get(0).toolSpecifications().stream()
                .map(ToolSpecification::name).toList();
        assertTrue(offered.contains("web.search"),
                () -> "the belted web.search must be offered under the wired identity; offered=" + offered);
        assertTrue(offered.contains("fs.read"),
                () -> "the belted fs.read must be offered under the wired identity; offered=" + offered);

        // web.search EXECUTED (its actionable config-shaped message is a result, not an error) → audited ok.
        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-belt", "ok", "web.search"),
                "the belted web.search must execute and audit ok");

        // The tool result reached the model's SECOND request (the actionable message the tool returned).
        List<ChatMessage> secondRequest = model.capturedRequests().get(1).messages();
        assertTrue(secondRequest.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage r
                        && r.text() != null && r.text().contains("braveApiKey")),
                () -> "the tool's actionable message must reach the model's next request; second=" + secondRequest);
    }

    public static class WiredHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-default-belt-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                String belt = InitCommand.DEFAULT_ALLOWED_TOOLS.stream()
                        .map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-web-search:m\", "
                      + "\"identityId\": \"" + InitCommand.DEFAULT_IDENTITY_ID + "\", "
                      + "\"allowedTools\": [" + belt + "] }");
                Files.createDirectories(home.resolve("identities"));
                Files.writeString(home.resolve("identities").resolve("default.json"),
                        "{ \"channelAccounts\": {} }");
                Path tools = Files.createDirectories(home.resolve("tools"));
                // Brave-no-key: the invoke resolves to the config-shaped message with ZERO network (#192).
                Files.writeString(tools.resolve("web.json"), "{ \"backend\": \"brave\" }");
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
