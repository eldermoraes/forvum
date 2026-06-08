package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.sdk.ChannelTurnDriver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * E2E scenario 10 (ULTRAPLAN §7.4 / X6): the CAPR dashboard. Drives five real turns through the SDK
 * {@code ChannelTurnDriver} (the engine's {@code TurnService}) against the in-process fake model — each
 * successful turn writes one {@code capr_events} row via {@code CaprRecorder} (M18) — then performs a
 * real HTTP {@code GET /q/dashboard/capr} against the running server and asserts the endpoint returns a
 * JSON array with at least five entries (ULTRAPLAN §7.4 expectation: "the endpoint returns a JSON summary
 * with at least five entries in {@code capr_events}").
 *
 * <p>The endpoint is {@code CaprDashboardRoute} (a {@code quarkus-reactive-routes} {@code @Route} over the
 * already-present {@code vertx-http} server). v0.1 ships judge mode off, so every completed turn is a
 * {@code passed=1}/{@code judgeModel="none"} verdict row; the endpoint surfaces those raw rows. This is
 * the non-live counterpart to the provider scripts — no real LLM (per the perf-gate convention, the suite
 * excludes inference via {@code FakeModelProvider}).
 *
 * <p>Each {@code dispatch} uses a DISTINCT native user id so the five turns land in five distinct sessions
 * (the session is keyed {@code channelId:nativeUserId}); driving five messages on one session would also
 * write five rows, but distinct ids keep the turns independent and avoid any history-growth coupling.
 */
@QuarkusTest
@TestProfile(CaprDashboardE2E.FakeBackedHomeProfile.class)
class CaprDashboardE2E {

    private static final int TURNS = 5;

    @Inject
    ChannelTurnDriver turns;

    @TestHTTPResource("/q/dashboard/capr")
    URI caprUri;

    @Test
    void fiveTurnsProduceAtLeastFiveCaprRowsExposedAsJson() throws Exception {
        for (int i = 0; i < TURNS; i++) {
            ChannelMessage message =
                    new ChannelMessage("e2e-capr", "user-" + i, "ping " + i, Instant.now());
            turns.dispatch(message, event -> { /* terminal Done carries the reply; CAPR row written by respond() */ });
        }

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(caprUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(),
                () -> "GET /q/dashboard/capr must return 200; body: " + response.body());

        JsonNode body = new ObjectMapper().readTree(response.body());
        assertTrue(body.isArray(), () -> "the endpoint must return a JSON array; got: " + response.body());
        assertTrue(body.size() >= TURNS,
                () -> "expected at least " + TURNS + " capr_events entries after " + TURNS
                        + " turns; got " + body.size() + ": " + response.body());

        // The rows are the v0.1 judge-disabled verdicts: passed=1, judgeModel="none".
        JsonNode first = body.get(0);
        assertEquals(1, first.get("passed").asInt(), "v0.1 records every completed turn as passed=1");
        assertEquals("none", first.get("judgeModel").asText(), "v0.1 judge mode is off (judgeModel=none)");
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so the five turns need no LLM. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-capr-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
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
