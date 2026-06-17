package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.approval.ApprovalStore;

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
import java.util.Map;

/**
 * E2E for the P2-14 #39 web approval surface ({@link ai.forvum.app.ApprovalDashboardRoute}). Seeds pending
 * {@code tool_approvals} rows directly (simulating confirm-required calls parked by a now-gone process — the
 * restart case), then drives the real HTTP endpoints: {@code GET /q/dashboard/approvals} must list them, a
 * {@code POST .../reject} must resolve and remove one, and a {@code POST} on an unknown id must report
 * {@code handled=false}. No live model — the queue rows are seeded, not produced by a turn.
 */
@QuarkusTest
@TestProfile(ApprovalDashboardE2E.FakeBackedHomeProfile.class)
class ApprovalDashboardE2E {

    @Inject
    ApprovalStore store;

    @TestHTTPResource("/q/dashboard/approvals")
    URI approvalsUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pendingRowsAreListedAndResolvableOverHttp() throws Exception {
        String idA = store.createPending("web:tab-A", "main", "shell.exec", "{\"cmd\":\"ls\"}", "list files");
        String idB = store.createPending("web:tab-B", "main", "shell.exec", "{\"cmd\":\"rm x\"}", "delete x");

        // GET lists both pending requests.
        JsonNode list = getJson(approvalsUri);
        assertTrue(list.isArray(), () -> "the endpoint must return a JSON array; got " + list);
        assertTrue(containsId(list, idA) && containsId(list, idB),
                () -> "both seeded pending rows must be listed; got " + list);

        // POST reject resolves idA: handled, and it disappears from the pending list + is marked rejected.
        JsonNode rejected = postJson(decisionUri(idA, "reject"));
        assertTrue(rejected.get("handled").asBoolean(), () -> "rejecting a pending row must be handled; got " + rejected);
        assertEquals("rejected", store.statusOf(idA));

        JsonNode afterReject = getJson(approvalsUri);
        assertFalse(containsId(afterReject, idA), "a rejected row must leave the pending list");
        assertTrue(containsId(afterReject, idB), "the other row stays pending");

        // POST approve resolves idB (re-dispatch fires async; the row is marked approved synchronously).
        JsonNode approved = postJson(decisionUri(idB, "approve"));
        assertTrue(approved.get("handled").asBoolean());
        assertEquals("approved", store.statusOf(idB));

        // POST on an unknown id reports handled=false (HTTP 200, the client reads the flag).
        JsonNode unknown = postJson(decisionUri("does-not-exist", "approve"));
        assertFalse(unknown.get("handled").asBoolean(), () -> "an unknown id must not be handled; got " + unknown);
    }

    private URI decisionUri(String id, String action) {
        return URI.create(approvalsUri.toString() + "/" + id + "/" + action);
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode row : array) {
            if (id.equals(row.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode getJson(URI uri) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "GET " + uri + " must be 200; body: " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static JsonNode postJson(URI uri) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "POST " + uri + " must be 200; body: " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so any R1 re-dispatch needs no LLM. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-approval-e2e-home");
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
