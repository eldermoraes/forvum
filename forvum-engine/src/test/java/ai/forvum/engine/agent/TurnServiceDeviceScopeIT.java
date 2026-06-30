package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ChannelMessage;
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
import java.util.Map;

/**
 * #166 device {@code approvedScopes} intersection end-to-end through {@link TurnService#dispatch}: the
 * paired {@code web} device is scope-governed — it REQUESTS FS_WRITE but the owner APPROVED only FS_READ
 * — so a turn arriving on the web channel is capped to the device's approvedScopes. The scripted model
 * emits an {@code fs.write} call; the effective scope set is {@code callerScopes ∩ agentCap ∩ {FS_READ}},
 * which omits FS_WRITE, so the executor denies + audits it on the SAME synchronous virtual thread the
 * scope binding lives on.
 *
 * <p>This proves two acceptance criteria at once: (a) effective scopes equal the intersection of upstream
 * authorization and the device's approvedScopes; and (b) a PENDING upgrade (FS_WRITE is requested but not
 * approved) never grants the scope — only approvedScopes are intersected, so it never reaches
 * {@code CURRENT_EFFECTIVE_SCOPES}.
 *
 * <p>Contrast with {@link TurnServiceRbacIT#anIdentityWithoutRolesRunsTheSameToolDrivenThroughDispatch}
 * (the same permissive caller + agent, but NO device scope governance &rarr; the write runs): the only
 * difference is the device's approvedScopes ceiling, proving the device cap — not the caller — restricts.
 * Surefire-run (headless library, CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(TurnServiceDeviceScopeIT.DeviceScopeHomeProfile.class)
class TurnServiceDeviceScopeIT {

    @Inject
    TurnService turns;

    @Test
    void aDeviceApprovedScopeSetCapsTheTurnAndDeniesAToolOutsideIt() {
        turns.dispatch(new ChannelMessage("web", "sess-d", "write something", Instant.now()), e -> { });

        assertEquals(1L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-d", "denied", "fs.write"),
                "a device whose approvedScopes omit FS_WRITE must deny fs.write (the pending upgrade never grants it)");
        assertEquals(0L, ToolInvocationEntity.count(
                "sessionId = ?1 and status = ?2 and toolName = ?3", "web:sess-d", "ok", "fs.write"),
                "the capped call must never run");
    }

    /**
     * Seeds {@code main} (scripted model emitting fs.write, belt fs.write, no roles &rarr; permissive
     * caller + no agent cap), a scope-governed {@code web} device (REQUESTS FS_WRITE, APPROVED only
     * FS_READ), and the {@code openweb} identity (no roles) mapped to (web, sess-d).
     */
    public static class DeviceScopeHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-device-scope-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted:m\", \"allowedTools\": [\"fs.write\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("openweb.json"),
                        "{ \"displayName\": \"Open\", \"channelAccounts\": { \"web\": \"sess-d\" } }");
                Path devices = Files.createDirectories(home.resolve("devices"));
                Files.writeString(devices.resolve("web.json"),
                        "{ \"token\": \"w\", \"identityId\": \"openweb\", "
                      + "\"requestedScopes\": [\"FS_WRITE\"], \"approvedScopes\": [\"FS_READ\"] }");
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
