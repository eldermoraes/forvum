package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * #166 device-token authentication end-to-end across the whole app stack, coordinating with the #165
 * authenticated-principal seam. A paired device declares its own token in {@code devices/web.json}
 * (distinct from the operator secret). Two boundaries are proven:
 *
 * <ul>
 *   <li><b>The mechanism + split HTTP policy.</b> {@code OperatorAuthMechanism} now matches a token that
 *       is not the operator secret against every device's token and mints a {@code device}-role identity.
 *       The dashboards stay operator-only, so the device token is <em>recognized but forbidden</em> there
 *       ({@code 403}, NOT {@code 401} anonymous) — proving the device path fired; the operator token is
 *       {@code 200}; an unknown token is {@code 401}.</li>
 *   <li><b>The channel adapter propagates the credential.</b> The device token admits {@code /ws/chat}
 *       (the chat policy is operator OR device), {@code ChatSocket} reads the device principal + handshake
 *       token and propagates a {@code DeviceCredential}, and the engine re-authenticates it and drives the
 *       turn — the reply ({@code "pong"}) streams back over the socket.</li>
 * </ul>
 *
 * <p>Non-live (the in-process {@code FakeModelProvider}); the device declares no {@code approvedScopes}
 * here, so the turn is not scope-capped (the cap is proven by the engine {@code TurnServiceDeviceScopeIT}).
 */
@QuarkusTest
@TestProfile(DeviceTokenWebAuthE2E.DeviceAuthHomeProfile.class)
class DeviceTokenWebAuthE2E {

    static final String OPERATOR_TOKEN = "op-secret-166";
    static final String DEVICE_TOKEN = "dev-secret-166";

    @TestHTTPResource("/ws/chat")
    URI chatUri;

    @TestHTTPResource("/q/dashboard/capr")
    URI caprUri;

    @Test
    void aDeviceTokenIsRecognizedAsDeviceAndForbiddenOnTheOperatorOnlyDashboard() throws Exception {
        assertEquals(200, dashboardStatus(OPERATOR_TOKEN),
                "the operator token is authorized on the dashboard");
        assertEquals(403, dashboardStatus(DEVICE_TOKEN),
                "a device token is recognized (device role) but the dashboard is operator-only — 403, not 401");
        assertEquals(401, dashboardStatus("not-a-real-token"),
                "an unknown token is unauthorized");
    }

    @Test
    void aDeviceTokenIsAdmittedOnTheChatSocketAndDrivesATurn() throws InterruptedException {
        LinkedBlockingDeque<String> messages = new LinkedBlockingDeque<>();

        WebSocketClientConnection connection = BasicWebSocketConnector.create()
                .baseUri(URI.create(chatUri + "?access_token=" + DEVICE_TOKEN))
                .onTextMessage((c, message) -> messages.add(message))
                .connectAndAwait();
        try {
            connection.sendTextAndAwait("hello");

            String reply = messages.poll(10, TimeUnit.SECONDS);
            assertEquals("pong", reply,
                    "the device-authenticated connection drove a real turn end-to-end");
        } finally {
            connection.closeAndAwait();
        }
    }

    private int dashboardStatus(String token) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(caprUri).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** Seeds {@code main} on the fake provider and a paired {@code web} device with its OWN token. */
    public static class DeviceAuthHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-device-auth-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path devices = Files.createDirectories(home.resolve("devices"));
                Files.writeString(devices.resolve("web.json"),
                        "{ \"token\": \"" + DEVICE_TOKEN + "\", \"identityId\": \"webuser\" }");
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
