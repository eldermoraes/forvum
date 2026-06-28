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
 * E2E for the #165 authorization of the P2-14 approval surface ({@link ai.forvum.app.ApprovalDashboardRoute}).
 * The approval dashboard is an operator control plane: a {@code POST .../approve} resumes a parked turn and
 * dispatches the approved tool call, so it must never be reachable anonymously. This seeds pending
 * {@code tool_approvals} rows directly (the restart case — a process-gone parked call) and drives the real
 * HTTP endpoints with and without the operator credential, asserting the {@code quarkus.http.auth.permission}
 * policy + {@link ai.forvum.app.OperatorAuthMechanism}:
 *
 * <ul>
 *   <li>anonymous / wrong-token GET + POST → {@code 401} (no listing leak);
 *   <li>an unauthorized approve must NOT resolve or dispatch the parked call (the no-escalation regression);
 *   <li>the operator (a valid {@code Authorization: Bearer} token) can list, reject, and approve;
 *   <li>an unknown id returns {@code handled=false} (HTTP 200) without revealing whether it existed.
 * </ul>
 *
 * <p>No live model — the queue rows are seeded, and {@code main} is pinned to the in-process {@code fake}
 * provider so an approve's R1 re-dispatch needs no LLM. The operator token is supplied via
 * {@code forvum.operator.token} (the config-override branch of {@link ai.forvum.app.OperatorCredentialStore}).
 */
@QuarkusTest
@TestProfile(ApprovalDashboardE2E.FakeBackedHomeProfile.class)
class ApprovalDashboardE2E {

    static final String OPERATOR_TOKEN = "test-operator-secret-165";

    @Inject
    ApprovalStore store;

    @TestHTTPResource("/q/dashboard/approvals")
    URI approvalsUri;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anonymousAndWrongTokenAreRejectedAndCannotDispatch() throws Exception {
        String id = store.createPending("web:tab-A", "main", "shell.exec", "{\"cmd\":\"rm -rf /\"}", "danger");

        // GET without a token must be 401 — pending tool calls (with their arguments) are not listed anonymously.
        assertEquals(401, send(approvalsUri, "GET", null).statusCode(), "anonymous GET must be 401");

        // POST approve without a token must be 401 AND must not resolve or dispatch the parked tool call.
        assertEquals(401, send(decisionUri(id, "approve"), "POST", null).statusCode(),
                "anonymous approve must be 401");
        assertEquals("pending", store.statusOf(id),
                "an unauthorized approve must not resolve or dispatch the parked tool call");

        // A wrong token is also 401, and still cannot dispatch.
        assertEquals(401, send(decisionUri(id, "approve"), "POST", "not-the-token").statusCode(),
                "a wrong token must be 401");
        assertEquals("pending", store.statusOf(id), "a wrong-token approve must not dispatch either");
    }

    @Test
    void operatorCanListRejectAndApprove() throws Exception {
        String idA = store.createPending("web:tab-A", "main", "shell.exec", "{\"cmd\":\"ls\"}", "list files");
        String idB = store.createPending("web:tab-B", "main", "shell.exec", "{\"cmd\":\"rm x\"}", "delete x");

        JsonNode list = okJson(send(approvalsUri, "GET", OPERATOR_TOKEN));
        assertTrue(list.isArray(), () -> "the endpoint must return a JSON array; got " + list);
        assertTrue(containsId(list, idA) && containsId(list, idB),
                () -> "both seeded pending rows must be listed for the operator; got " + list);

        JsonNode rejected = okJson(send(decisionUri(idA, "reject"), "POST", OPERATOR_TOKEN));
        assertTrue(rejected.get("handled").asBoolean(), () -> "rejecting a pending row must be handled; got " + rejected);
        assertEquals("rejected", store.statusOf(idA));

        JsonNode afterReject = okJson(send(approvalsUri, "GET", OPERATOR_TOKEN));
        assertFalse(containsId(afterReject, idA), "a rejected row must leave the pending list");
        assertTrue(containsId(afterReject, idB), "the other row stays pending");

        JsonNode approved = okJson(send(decisionUri(idB, "approve"), "POST", OPERATOR_TOKEN));
        assertTrue(approved.get("handled").asBoolean());
        assertEquals("approved", store.statusOf(idB));

        // Unknown id with a valid token: handled=false (HTTP 200), the client reads the flag.
        JsonNode unknown = okJson(send(decisionUri("does-not-exist", "approve"), "POST", OPERATOR_TOKEN));
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

    /** Send GET/POST to {@code uri}, adding {@code Authorization: Bearer <token>} when {@code token} is non-null. */
    private static HttpResponse<String> send(URI uri, String method, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode okJson(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(),
                () -> "expected 200 for the operator; body: " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** Seeds {@code main} (fake provider) AND the operator token so the auth E2E needs no LLM and no key file. */
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
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.operator.token", OPERATOR_TOKEN);
        }
    }
}
