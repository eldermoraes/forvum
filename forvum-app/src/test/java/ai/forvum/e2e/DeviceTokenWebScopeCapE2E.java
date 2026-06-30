package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.persistence.ToolInvocationEntity;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * #166 device {@code approvedScopes} enforcement on the Web channel, end-to-end THROUGH {@code ChatSocket}
 * + the handshake {@code SecurityIdentity} (closing the review's MEDIUM gap — the engine
 * {@code TurnServiceDeviceScopeIT} drives the credential directly, bypassing the channel adapter, so the
 * security-critical "the WebSocket callback's SecurityIdentity carries the device role → credentialFor
 * returns a PRESENT credential → the cap runs" path was unverified).
 *
 * <p>The paired {@code web} device's {@code approvedScopes} omit FS_WRITE; the agent's belt INCLUDES
 * {@code fs.write} and the caller is the PERMISSIVE fallback identity (every scope), so the ONLY thing
 * that can deny the scripted {@code fs.write} is the device scope-cap. A device-authenticated WebSocket
 * connection drives a turn; the scripted model emits {@code fs.write}; the engine intersects the device's
 * {@code approvedScopes} ({@code FS_READ}) into the effective scopes, so {@code fs.write} (FS_WRITE) is
 * denied + audited, and the model's follow-up ({@code "refused"}) streams back.
 *
 * <p>Red-check: were {@code credentialFor} to fall to {@link ai.forvum.core.DeviceCredential#ABSENT}
 * (e.g. the callback SecurityIdentity not carrying the {@code device} role), the cap would not run, the
 * permissive caller would let {@code fs.write} execute, and the {@code denied} assertion would fail.
 */
@QuarkusTest
@TestProfile(DeviceTokenWebScopeCapE2E.ScopeCapHomeProfile.class)
class DeviceTokenWebScopeCapE2E {

    static final String OPERATOR_TOKEN = "op-secret-166-cap";
    static final String DEVICE_TOKEN = "dev-secret-166-cap";

    @TestHTTPResource("/ws/chat")
    URI chatUri;

    @Test
    void aDeviceAuthenticatedSocketHasItsApprovedScopesEnforcedOnTheTurn() throws InterruptedException {
        LinkedBlockingDeque<String> messages = new LinkedBlockingDeque<>();

        WebSocketClientConnection connection = BasicWebSocketConnector.create()
                .baseUri(URI.create(chatUri + "?access_token=" + DEVICE_TOKEN))
                .onTextMessage((c, message) -> messages.add(message))
                .connectAndAwait();
        try {
            connection.sendTextAndAwait("please write a file");

            // The scripted model emits fs.write, the engine denies it (device cap), then the model's
            // follow-up "refused" streams back — the reply means the turn (and its denied audit) completed.
            String reply = messages.poll(10, TimeUnit.SECONDS);
            assertEquals("refused", reply, "the device-authenticated turn completed after the denied tool");
        } finally {
            connection.closeAndAwait();
        }

        // The fs.write is in the belt and the caller is permissive — so a denial can ONLY come from the
        // device's approvedScopes cap, which is reachable ONLY if ChatSocket propagated a PRESENT credential.
        assertTrue(ToolInvocationEntity.count("status = ?1 and toolName = ?2", "denied", "fs.write") >= 1L,
                "the web device's approvedScopes (FS_READ) must deny the scripted fs.write (FS_WRITE)");
        assertEquals(0L, ToolInvocationEntity.count("status = ?1 and toolName = ?2", "ok", "fs.write"),
                "the capped fs.write must never have executed");
    }

    /**
     * Seeds {@code main} (scripted-injection model emitting fs.write, belt fs.write, fallback identity
     * {@code webuser}), a PERMISSIVE {@code webuser} identity (no roles → default-user, every scope), and a
     * paired {@code web} device whose {@code approvedScopes} omit FS_WRITE.
     */
    public static class ScopeCapHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-device-scopecap-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"scripted-injection:m\", \"allowedTools\": [\"fs.write\"], "
                      + "\"identityId\": \"webuser\" }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("webuser.json"),
                        "{ \"displayName\": \"Web User\" }");
                Path devices = Files.createDirectories(home.resolve("devices"));
                Files.writeString(devices.resolve("web.json"),
                        "{ \"token\": \"" + DEVICE_TOKEN + "\", \"identityId\": \"webuser\", "
                      + "\"approvedScopes\": [\"FS_READ\"] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.operator.token", OPERATOR_TOKEN);
        }
    }
}
