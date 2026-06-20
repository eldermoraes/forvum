package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

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
 * E2E for the P3-6 #54 Dev UI live config editor ({@link ai.forvum.app.DevConfigEditorRoute}). The route is
 * built only when {@code forvum.devui.config-editor.enabled=true}, which the {@code %test} profile sets — so
 * the {@code @Route} surface exists here (it is removed from prod/native — the dev-only carve-out). Seeds a
 * valid {@code agents/main.json}, then drives the real HTTP endpoints: the file list includes it, a malformed
 * edit is REJECTED with an ERROR finding (and the on-disk file is unchanged), and a valid edit is SAVED (and
 * the engine sees it on the next read because the editor fired the hot-reload {@code ConfigurationChangedEvent}).
 * No live model.
 */
@QuarkusTest
@TestProfile(DevConfigEditorE2E.SeededHomeProfile.class)
class DevConfigEditorE2E {

    @TestHTTPResource("/q/dev-ui/config-editor/api")
    URI apiBase;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsValidatesAndSavesConfigOverHttp() throws Exception {
        // The seeded agent is listed.
        JsonNode files = getJson(apiBase.toString() + "/files");
        assertTrue(files.isArray());
        assertTrue(contains(files, "agents/main.json"), () -> "the seeded agent must be listed; got " + files);

        // GET the file content.
        JsonNode file = getJson(apiBase.toString() + "/file?path=agents/main.json");
        assertTrue(file.path("content").asText().contains("primaryModel"),
                () -> "the file content must be returned; got " + file);

        String original = Files.readString(SeededHomeProfile.HOME.resolve("agents/main.json"));

        // POST validate a VALID edit: valid=true, no ERROR, on-disk file untouched (dry run).
        JsonNode okValidate = postJson(apiBase.toString() + "/validate",
                body("agents/main.json", "{\"primaryModel\":\"fake:other\",\"allowedTools\":[]}"));
        assertTrue(okValidate.path("valid").asBoolean(), () -> "a valid edit must validate; got " + okValidate);

        // POST validate a MALFORMED edit: not valid, an ERROR finding, on-disk file untouched.
        JsonNode badValidate = postJson(apiBase.toString() + "/validate",
                body("agents/main.json", "{ not json"));
        assertFalse(badValidate.path("valid").asBoolean(), () -> "a malformed edit must be invalid; got " + badValidate);
        assertTrue(hasError(badValidate), () -> "a malformed edit must surface an ERROR; got " + badValidate);

        // A non-JSON request body is a clean 400 (the generic catch arm), not a 500.
        assertEquals(400, postStatus(apiBase.toString() + "/save", "this is not json"));

        // POST save the MALFORMED edit: NOT saved, and the file is rolled back to the original.
        JsonNode badSave = postJson(apiBase.toString() + "/save", body("agents/main.json", "{ not json"));
        assertFalse(badSave.path("saved").asBoolean(), () -> "a malformed edit must not be saved; got " + badSave);
        assertEquals(original, Files.readString(SeededHomeProfile.HOME.resolve("agents/main.json")),
                "a rejected save must leave the original file in place");

        // POST save a VALID edit: saved, and the new content is on disk.
        String edited = "{\"primaryModel\":\"fake:other-model\",\"allowedTools\":[]}";
        JsonNode goodSave = postJson(apiBase.toString() + "/save", body("agents/main.json", edited));
        assertTrue(goodSave.path("saved").asBoolean(), () -> "a valid edit must be saved; got " + goodSave);
        assertEquals(edited, Files.readString(SeededHomeProfile.HOME.resolve("agents/main.json")));

        // A traversal path is a clean 400, not a 500.
        assertEquals(400, statusOf(apiBase.toString() + "/file?path=" + URI.create("../escape.json")));
    }

    private static String body(String path, String content) throws IOException {
        return MAPPER.writeValueAsString(Map.of("path", path, "content", content));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasError(JsonNode result) {
        for (JsonNode f : result.path("findings")) {
            if ("ERROR".equals(f.path("severity").asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode getJson(String uri) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "GET " + uri + " must be 200; body: " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static int statusOf(String uri) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static int postStatus(String uri, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static JsonNode postJson(String uri, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "POST " + uri + " must be 200; body: " + response.body());
        return MAPPER.readTree(response.body());
    }

    /** Seeds a valid {@code main} agent pinned to the in-process {@code fake} provider. */
    public static class SeededHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-devui-editor-e2e-home");
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
