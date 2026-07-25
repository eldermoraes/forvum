package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.engine.agent.TurnService;

import dev.langchain4j.agent.tool.ToolSpecification;

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
 * The executable red-check of the #184 D2 mechanism: WITHOUT the scaffold's identity wiring, the belt is
 * invisible. A {@code main} agent with the SAME widened belt but NO {@code identityId} (and no identity
 * mapping any channel account) resolves every turn to the anonymous identity (no scopes), so
 * {@code scopeVisibleBelt} filters the ENTIRE belt and the model is offered ZERO tools — the exact failure
 * the scaffold's {@code identityId} closes. Compare {@code DefaultBeltTurnTest}, which offers the belt with
 * the wiring present. Removing the identityId from {@code DefaultBeltTurnTest}'s fixture reproduces this.
 */
@QuarkusTest
@TestProfile(AnonymousBeltInvisibleTest.BareHomeProfile.class)
class AnonymousBeltInvisibleTest {

    @Inject
    TurnService turns;

    @Inject
    ScriptedWebSearchModelProvider model;

    @BeforeEach
    void resetCapture() {
        model.reset();
    }

    @Test
    void withoutIdentityWiringNoToolIsOffered() {
        List<AgentEvent> events = new ArrayList<>();
        ChannelMessage message = new ChannelMessage("web", "sess-anon", "search the web", Instant.now());

        turns.dispatch(message, events::add);

        List<String> offered = model.capturedRequests().get(0).toolSpecifications().stream()
                .map(ToolSpecification::name).toList();
        // The engine ALWAYS offers the built-in spawn_worker; the point is that NONE of the belt's tools are
        // offered — scopeVisibleBelt filtered the whole belt because the anonymous identity has no scopes.
        assertFalse(offered.contains("web.search"),
                () -> "an anonymous turn must NOT be offered the belted web.search; offered=" + offered);
        assertFalse(offered.contains("fs.read"),
                () -> "an anonymous turn must NOT be offered the belted fs.read; offered=" + offered);
    }

    public static class BareHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-anon-belt-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                String belt = InitCommand.DEFAULT_ALLOWED_TOOLS.stream()
                        .map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
                // NO identityId, and no identities/ mapping — the pre-#184 dead-by-anonymity shape.
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-web-search:m\", "
                      + "\"allowedTools\": [" + belt + "] }");
                Path tools = Files.createDirectories(home.resolve("tools"));
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
