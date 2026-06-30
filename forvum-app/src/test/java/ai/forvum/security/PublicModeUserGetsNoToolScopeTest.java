package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
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
 * Security-test layer for #170: "public mode does not implicitly grant privileged tool scopes". A
 * public-mode channel ({@code allowAllUsers}) ADMITS an unmapped sender — that admission is the channel's
 * concern, proven by the per-channel config tests. This test proves the other half: an admitted but
 * UNMAPPED sender resolves to the {@code anonymous} identity (#168) with the EMPTY scope set, so a tool
 * that IS in the agent's belt but requires a privileged scope ({@code fs.write} → {@code FS_WRITE}) is
 * refused by the RBAC scope gate ({@code ToolExecutor} reading {@code CURRENT_EFFECTIVE_SCOPES}) and
 * audited {@code denied}, the filesystem provider never runs, and the turn still completes.
 *
 * <p>This is distinct from {@code PromptInjectionToolDeniedTest} (empty belt → the BELT gate denies a
 * miss): here the tool IS in the belt, so the denial is the SCOPE gate firing for an anonymous principal —
 * exactly the "an open channel cannot reach a privileged tool" composition #170 relies on. Driven through
 * the real {@link TurnService#dispatch} on the assembled app with the in-process
 * {@link ScriptedInjectionModelProvider} (no live model, no network), so it runs in the default build.
 */
@QuarkusTest
@TestProfile(PublicModeUserGetsNoToolScopeTest.PublicScopeHomeProfile.class)
class PublicModeUserGetsNoToolScopeTest {

    @Inject
    TurnService turns;

    @Test
    void anAdmittedAnonymousUserIsDeniedAnInBeltPrivilegedTool() {
        List<AgentEvent> events = new ArrayList<>();
        // An unmapped sender (no identities/*.json maps "anon-1" on "telegram") — the state a public-mode
        // channel admits. The model is scripted to attempt fs.write, which IS in the agent's belt.
        ChannelMessage admitted = new ChannelMessage("telegram", "anon-1", "hello there", Instant.now());

        turns.dispatch(admitted, events::add);

        // The turn completes normally — the denial is handled, not a crash.
        assertTrue(events.stream().anyMatch(Done.class::isInstance),
                "the turn must complete with a terminal Done despite the denied tool call");
        assertTrue(events.stream().noneMatch(ErrorEvent.class::isInstance),
                "a denied tool call must not fail the turn (no ErrorEvent)");

        // fs.write is in the belt, so this denial is the RBAC SCOPE gate (anonymous has no FS_WRITE), not a
        // belt miss — audited denied, scoped to this method's session (the shared @TestProfile DB).
        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "telegram:anon-1", "denied", "fs.write"),
                "an admitted anonymous (public-mode) user must be denied the in-belt privileged fs.write");

        // No escalation: the filesystem provider never executed for this session.
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "telegram:anon-1", "ok", "fs.write"),
                "the denied tool must never have executed (no privileged-scope escalation for a public user)");
    }

    /**
     * Seeds a {@code main} agent whose belt GRANTS {@code fs.write} (so the belt gate passes and the SCOPE
     * gate is the denier), no fallback {@code identityId} (an unmapped user resolves to anonymous), pinned
     * to the scripted-injection provider. No {@code identities/} mapping, so the inbound user is unmapped.
     */
    public static class PublicScopeHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-public-scope-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-injection:m\", \"allowedTools\": [\"fs.write\"] }");
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
