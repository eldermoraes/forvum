package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jboss.logmanager.ExtLogRecord;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The WhatsApp webhook over a REAL (in-JVM) {@code vertx-http} server: the GET verification handshake and
 * the signed-POST gate, driven over HTTP with the JDK client. {@code forvum.home} is pinned (test
 * {@code application.properties}) to a build path; each test writes/deletes {@code channels/whatsapp.json}
 * to exercise the configured and the no-config (native-smoke) cases. The reply path's Graph client points
 * at a dead port (also test config), so a valid POST acks and dispatches but sends nothing real. Boots
 * Quarkus in-JVM; runs under Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class WhatsAppWebhookIT {

    private static final String VERIFY_TOKEN = "vt-secret";
    private static final String APP_SECRET = "app-secret-xyz";
    private static final Path CONFIG =
            Path.of("target/whatsapp-it-home").resolve("channels").resolve("whatsapp.json");

    private static final String TEXT_PAYLOAD = """
            { "entry": [ { "changes": [ { "value": { "messages": [
                { "from": "15550001111", "type": "text", "text": { "body": "hi over webhook" } } ] } } ] } ] }
            """;

    private static final String MULTI_PAYLOAD = """
            { "entry": [ { "changes": [ { "value": { "messages": [
                { "from": "15550001111", "type": "text", "text": { "body": "first" } },
                { "from": "15557772222", "type": "text", "text": { "body": "second" } } ] } } ] } ] }
            """;

    @TestHTTPResource("/webhooks/whatsapp")
    URL webhookUrl;

    @Inject
    WhatsAppFakeTurnDriver driver;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        driver.reset();
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(CONFIG);
    }

    private static void writeConfig() throws Exception {
        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, "{ \"verifyToken\": \"" + VERIFY_TOKEN + "\", \"appSecret\": \""
                + APP_SECRET + "\", \"accessToken\": \"at\", \"phoneNumberId\": \"PNID\" }");
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return "sha256=" + hex;
    }

    private HttpResponse<String> get(String query) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(webhookUrl.toString() + "?" + query)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(webhookUrl.toURI())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            b.header(WhatsAppSignature.HEADER, signature);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitDispatched(int min) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && driver.dispatched().size() < min) {
            Thread.sleep(50);
        }
    }

    @Test
    void verificationEchoesTheChallengeWithTheRightToken() throws Exception {
        writeConfig();
        HttpResponse<String> resp =
                get("hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=1234567");
        assertEquals(200, resp.statusCode());
        assertEquals("1234567", resp.body(), "the verification echoes hub.challenge verbatim");
    }

    @Test
    void verificationRejectsAWrongToken() throws Exception {
        writeConfig();
        HttpResponse<String> resp =
                get("hub.mode=subscribe&hub.verify_token=WRONG&hub.challenge=1234567");
        assertEquals(403, resp.statusCode());
    }

    @Test
    void verificationRejectsWhenUnconfigured() throws Exception {
        Files.deleteIfExists(CONFIG); // no channels/whatsapp.json → empty spec, no verifyToken
        HttpResponse<String> resp =
                get("hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=1234567");
        assertEquals(403, resp.statusCode(), "an unconfigured channel cannot complete verification");
    }

    @Test
    void aValidlySignedPostIsAckedAndDrivesTheTurn() throws Exception {
        writeConfig();
        HttpResponse<String> resp = post(TEXT_PAYLOAD, sign(TEXT_PAYLOAD, APP_SECRET));
        assertEquals(200, resp.statusCode(), "a valid event is acked immediately");
        awaitDispatched(1);
        assertEquals(1, driver.dispatched().size(), "the text message drove a turn (async on a VT)");
        assertEquals("hi over webhook", driver.dispatched().get(0).content());
        assertEquals("15550001111", driver.dispatched().get(0).nativeUserId());
    }

    @Test
    void aPostWithAnInvalidSignatureIsRejectedAndDrivesNoTurn() throws Exception {
        writeConfig();
        HttpResponse<String> resp = post(TEXT_PAYLOAD, "sha256=deadbeef");
        assertEquals(403, resp.statusCode());
        assertTrue(driver.dispatched().isEmpty(),
                "a forged signature must be rejected before any turn (the 403 path submits no worker)");
    }

    @Test
    void aPostWithNoSignatureIsRejected() throws Exception {
        writeConfig();
        HttpResponse<String> resp = post(TEXT_PAYLOAD, null);
        assertEquals(403, resp.statusCode());
    }

    @Test
    void aPostIsRejectedWhenUnconfigured() throws Exception {
        Files.deleteIfExists(CONFIG); // no appSecret → cannot validate → reject
        HttpResponse<String> resp = post(TEXT_PAYLOAD, sign(TEXT_PAYLOAD, APP_SECRET));
        assertEquals(403, resp.statusCode());
        assertTrue(driver.dispatched().isEmpty());
    }

    @Test
    void verificationRejectsANonSubscribeMode() throws Exception {
        writeConfig();
        HttpResponse<String> resp =
                get("hub.mode=unsubscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=1234567");
        assertEquals(403, resp.statusCode(), "only hub.mode=subscribe completes verification");
    }

    @Test
    void verificationRejectsAMissingChallenge() throws Exception {
        writeConfig();
        HttpResponse<String> resp = get("hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN);
        assertEquals(403, resp.statusCode(), "no hub.challenge → nothing to echo → reject");
    }

    @Test
    void verificationIsRejectedWhenTheChannelIsDisabled() throws Exception {
        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, "{ \"enabled\": false, \"verifyToken\": \"" + VERIFY_TOKEN
                + "\", \"appSecret\": \"" + APP_SECRET + "\", \"accessToken\": \"at\","
                + " \"phoneNumberId\": \"PNID\" }");
        HttpResponse<String> resp =
                get("hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=1234567");
        assertEquals(403, resp.statusCode(), "a disabled channel does not verify even when credentialed");
    }

    @Test
    void aBatchedMultiMessagePostDrivesOneTurnPerMessage() throws Exception {
        writeConfig();
        HttpResponse<String> resp = post(MULTI_PAYLOAD, sign(MULTI_PAYLOAD, APP_SECRET));
        assertEquals(200, resp.statusCode());
        awaitDispatched(2);
        assertEquals(2, driver.dispatched().size(),
                "the webhook submits one VT worker per parsed message (the production loop at n>1)");
        assertEquals(List.of("first", "second"),
                driver.dispatched().stream().map(m -> m.content()).sorted().toList());
    }

    @Test
    void anEmptyBodyPostNeitherCrashesNorDrivesATurn() throws Exception {
        writeConfig();
        // The rawBody empty/null branch (WhatsAppWebhook lines 100-101): an empty POST body must NOT 500
        // and must drive NO turn — whether Vert.x surfaces it as an empty buffer (valid-over-empty HMAC →
        // 200, no messages) or a null body (→ 403). Pin the invariant, not the exact code path.
        HttpResponse<String> resp = post("", sign("", APP_SECRET));
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 403,
                "an empty body must be handled gracefully (no 500); got " + resp.statusCode());
        Thread.sleep(200); // let any (erroneously) submitted worker run
        assertTrue(driver.dispatched().isEmpty(), "an empty body carries no messages → no turn");
    }

    @Test
    void theTwoHundredIsAckedWhileTheTurnIsStillInFlight() throws Exception {
        writeConfig();
        CountDownLatch gate = driver.blockUntilReleased(); // dispatch will block until released
        long start = System.currentTimeMillis();
        HttpResponse<String> resp = post(TEXT_PAYLOAD, sign(TEXT_PAYLOAD, APP_SECRET));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(200, resp.statusCode());
        assertTrue(elapsed < 2000,
                "the 200 must be acked WITHOUT waiting for the (blocked) turn; took " + elapsed + " ms");
        assertTrue(driver.dispatched().isEmpty(), "the turn is still blocked behind the gate at ack time");

        gate.countDown();
        awaitDispatched(1);
        assertEquals(1, driver.dispatched().size(), "once released, the turn runs");
    }

    @Test
    void aTurnThatThrowsIsIsolatedAndItsFailureIsRedacted() throws Exception {
        writeConfig();
        driver.failWith(new RuntimeException("processing blew up; Authorization: Bearer leak-token-xyz"));
        List<String> logs = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r instanceof ExtLogRecord ext ? ext.getFormattedMessage() : r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(WhatsAppWebhook.class.getName());
        logger.addHandler(handler);
        try {
            HttpResponse<String> resp = post(TEXT_PAYLOAD, sign(TEXT_PAYLOAD, APP_SECRET));
            assertEquals(200, resp.statusCode(), "a throwing turn is isolated; the POST still acks");
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && logs.stream().noneMatch(m -> m.contains("Bearer <redacted>"))) {
                Thread.sleep(50);
            }
        } finally {
            logger.removeHandler(handler);
        }
        assertTrue(logs.stream().anyMatch(m -> m.contains("Bearer <redacted>")),
                "the worker-isolation catch must log the redacted failure; got: " + logs);
        assertTrue(logs.stream().noneMatch(m -> m.contains("leak-token-xyz")),
                "the access token must never reach the logs; got: " + logs);
    }
}
