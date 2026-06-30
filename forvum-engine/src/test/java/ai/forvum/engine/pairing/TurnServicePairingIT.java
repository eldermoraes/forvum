package ai.forvum.engine.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.DeviceCredential;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.agent.TurnService;
import ai.forvum.engine.persistence.ProviderCallEntity;
import ai.forvum.engine.persistence.SessionEntity;
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
 * P2-4 device pairing enforced at the real turn entry: {@link TurnService#dispatch} rejects an unpaired
 * device BEFORE the responder runs, and a paired device runs the turn while reusing its paired identity's
 * memory namespace (same {@code SessionEntity.identityId}). With {@code devices/} populated, pairing is
 * enabled, so an undeclared channel device is denied. Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServicePairingIT.PairingHomeProfile.class)
class TurnServicePairingIT {

    @Inject
    TurnService turns;

    @Test
    void anUnpairedDeviceIsRejectedAtTheTurnEntryBeforeTheResponderRuns() {
        List<AgentEvent> events = new ArrayList<>();

        // 'mobile' has no devices/mobile.json; pairing is enabled (devices/ is populated) → rejected.
        turns.dispatch(new ChannelMessage("mobile", "u-1", "hello", Instant.now()), events::add);

        assertEquals(1, events.size(), "an unpaired device yields a single terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "the rejected turn surfaces as a terminal ErrorEvent, never a TokenDelta/Done");
        assertEquals(DeviceNotPairedException.class.getName(), error.exceptionClass());
        assertTrue(error.message().contains("not paired"), "the message explains the device is unpaired");

        assertNull(SessionEntity.findById("mobile:u-1"),
                "the responder never ran — no session row was created for the unpaired device");
    }

    @Test
    void aPairedDeviceRunsTheTurnAndReusesItsPairedIdentityNamespace() {
        List<AgentEvent> events = new ArrayList<>();

        // 'web' is paired (devices/web.json → identity alice); alice maps (web, sess-a).
        turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), events::add);

        Done done = assertInstanceOf(Done.class, events.get(events.size() - 1),
                "a paired device completes the turn with a terminal Done");
        assertNotNull(done.turnId());

        SessionEntity session = SessionEntity.findById("web:sess-a");
        assertNotNull(session, "the paired turn created its session");
        assertEquals("alice", session.identityId,
                "the paired device shares alice's identity + memory namespace");
    }

    @Test
    void theLocalCliIsExemptSoEnablingPairingNeverLocksOutTheOperatorTerminal() {
        List<AgentEvent> events = new ArrayList<>();

        // No devices/cli.json exists, yet devices/ is populated (pairing enabled). The host operator CLI
        // ('forvum ask', channel DeviceRegistry.CLI) is exempt: it must still run, not be rejected — device
        // pairing pairs a SECOND device, the host terminal is the inherently-trusted primary surface (P2-4).
        turns.dispatch(new ChannelMessage(DeviceRegistry.CLI, "operator", "hello", Instant.now()), events::add);

        Done done = assertInstanceOf(Done.class, events.get(events.size() - 1),
                "the exempt cli turn completes with a terminal Done, NEVER a DeviceNotPairedException ErrorEvent");
        assertNotNull(done.turnId());
        assertNotNull(SessionEntity.findById(DeviceRegistry.CLI + ":operator"),
                "the exempt cli turn ran the responder and created its session");
    }

    @Test
    void aRevokedDeviceIsRejectedAtTheTurnEntryAndNoSessionRowIsCreated() {
        List<AgentEvent> events = new ArrayList<>();

        // 'oldphone' is declared but revoked (devices/oldphone.json → "revoked": true); pairing is enabled.
        turns.dispatch(new ChannelMessage("oldphone", "u-2", "hello", Instant.now()), events::add);

        assertEquals(1, events.size(), "a revoked device yields a single terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "the rejected turn surfaces as a terminal ErrorEvent, never a TokenDelta/Done");
        assertEquals(DeviceNotPairedException.class.getName(), error.exceptionClass());
        assertTrue(error.message().contains("revoked"), "the message explains the device is revoked");

        assertNull(SessionEntity.findById("oldphone:u-2"),
                "the responder never ran — no session row was created for the revoked device");
    }

    @Test
    void cronAndServerTurnsAreNotBlockedWhilePairingIsEnabled() {
        // The cron/server exempt devices short-circuit the guard even with devices/ populated (pairing on).
        // (cron turns reach agent.respond directly via CronScheduler.fire and never hit dispatch; this locks
        // the defensive belt: were a cron/server turn ever routed through dispatch, it must NOT be blocked.)
        for (String exempt : List.of(DeviceRegistry.CRON, DeviceRegistry.SERVER)) {
            List<AgentEvent> events = new ArrayList<>();
            turns.dispatch(new ChannelMessage(exempt, "svc", "hello", Instant.now()), events::add);

            assertInstanceOf(Done.class, events.get(events.size() - 1),
                    "the exempt '" + exempt + "' turn completes with a terminal Done, not a pairing rejection");
            assertNotNull(SessionEntity.findById(exempt + ":svc"),
                    "the exempt '" + exempt + "' turn ran the responder and created its session");
        }
    }

    // ---- #166 device-token authentication at the turn entry ----

    @Test
    void aValidDeviceTokenAuthenticatesAndCompletesTheTurn() {
        List<AgentEvent> events = new ArrayList<>();

        // The Web channel presents the device's token; it matches devices/web.json, so the turn runs.
        turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()),
                new DeviceCredential("web", "w-secret"), events::add);

        assertInstanceOf(Done.class, events.get(events.size() - 1),
                "a valid device token completes the turn with a terminal Done");
        assertNotNull(SessionEntity.findById("web:sess-a"), "the authenticated turn created its session");
    }

    @Test
    void aWrongDeviceTokenIsRejectedBeforeTheResponderWithZeroProviderAndToolCalls() {
        List<AgentEvent> events = new ArrayList<>();

        turns.dispatch(new ChannelMessage("web", "sess-bad", "hello", Instant.now()),
                new DeviceCredential("web", "WRONG-secret-value"), events::add);

        assertEquals(1, events.size(), "an unauthenticated device yields a single terminal ErrorEvent");
        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0),
                "an invalid token surfaces as a terminal ErrorEvent, never a TokenDelta/Done");
        assertEquals(DeviceAuthenticationException.class.getName(), error.exceptionClass());
        assertFalse(error.message().contains("WRONG-secret-value"),
                "secret hygiene: the presented token must never leak into the surfaced error");
        assertNull(SessionEntity.findById("web:sess-bad"),
                "the responder never ran — no session row for the rejected device");
        assertEquals(0L, ProviderCallEntity.count("sessionId = ?1", "web:sess-bad"),
                "a rejected authentication performs zero provider invocations");
        assertEquals(0L, ToolInvocationEntity.count("sessionId = ?1", "web:sess-bad"),
                "a rejected authentication performs zero tool invocations");
    }

    @Test
    void aMissingDeviceTokenIsRejectedWhenTheDeviceRequiresOne() {
        List<AgentEvent> events = new ArrayList<>();

        // A present credential with no token, against a device that declares one, is a missing-token failure.
        turns.dispatch(new ChannelMessage("web", "sess-missing", "hello", Instant.now()),
                new DeviceCredential("web", ""), events::add);

        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0));
        assertEquals(DeviceAuthenticationException.class.getName(), error.exceptionClass());
        assertNull(SessionEntity.findById("web:sess-missing"));
    }

    @Test
    void aCrossChannelDeviceCredentialIsRejected() {
        List<AgentEvent> events = new ArrayList<>();

        // A credential claiming device 'oldphone' presented on channel 'web' must not authorize the turn.
        turns.dispatch(new ChannelMessage("web", "sess-cross", "hello", Instant.now()),
                new DeviceCredential("oldphone", "w-secret"), events::add);

        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0));
        assertEquals(DeviceAuthenticationException.class.getName(), error.exceptionClass(),
                "a credential bound to one device/channel cannot authorize another");
        assertNull(SessionEntity.findById("web:sess-cross"));
    }

    /** Seeds {@code main} on the fake provider, a paired {@code web} device (identity alice), a revoked
     * {@code oldphone} device, and the identity alice mapped to (web, sess-a). No {@code devices/mobile.json},
     * so 'mobile' is unpaired; no {@code devices/cli.json}, so 'cli' relies on its built-in exemption. */
    public static class PairingHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-pairing-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", \"channelAccounts\": { \"web\": \"sess-a\" } }");
                Path devices = Files.createDirectories(home.resolve("devices"));
                Files.writeString(devices.resolve("web.json"),
                        "{ \"token\": \"w-secret\", \"identityId\": \"alice\" }");
                Files.writeString(devices.resolve("oldphone.json"),
                        "{ \"token\": \"o-secret\", \"identityId\": \"alice\", \"revoked\": true }");
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
