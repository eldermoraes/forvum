package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;
import ai.forvum.sdk.FileApiKeyStore;
import ai.forvum.sdk.ModelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flow tests for {@link ProviderAddCommand} (P2-10 #35), driven through the package-private
 * {@code run(...)} with a scripted {@link ProviderAddCommand.Prompt} and a smoke seam — no live LLM. The
 * {@code fake} provider on the app test classpath ({@code FakeModelProvider}, replies {@code "pong"})
 * exercises the real smoke path; a throwing/returning lambda covers the failure / no-reply branches.
 * {@code forvum.home} is a temp dir so writes never touch a real {@code ~/.forvum}.
 */
@QuarkusTest
@TestProfile(ProviderAddCommandTest.TempHomeProfile.class)
class ProviderAddCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCAFFOLD_MAIN =
            "{\n  \"primaryModel\": \"ollama:qwen3:1.7b\",\n  \"allowedTools\": [\"fs.read\"]\n}\n";

    @Inject
    ForvumHome home;

    @Inject
    Instance<ModelProvider> providers;

    @BeforeEach
    void resetHome() throws IOException {
        Path creds = home.state().resolve("credentials");
        if (Files.isDirectory(creds)) {
            try (Stream<Path> entries = Files.list(creds)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
        Path agents = home.agents();
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("main.json"), SCAFFOLD_MAIN);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private ProviderAddCommand command(String providerName, String model) {
        ProviderAddCommand command = new ProviderAddCommand();
        command.home = home;
        command.providers = providers;
        command.providerName = providerName;
        command.model = model;
        return command;
    }

    /** A prompt that returns the scripted responses in order, then {@code null} (EOF). */
    private static ProviderAddCommand.Prompt scripted(String... responses) {
        Iterator<String> it = List.of(responses).iterator();
        return new ProviderAddCommand.Prompt() {
            @Override
            public String line(String label) {
                return it.hasNext() ? it.next() : null;
            }

            @Override
            public String secret(String label) {
                return it.hasNext() ? it.next() : null;
            }
        };
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private String primaryModelOfMain() throws IOException {
        JsonNode spec = MAPPER.readTree(Files.readString(home.agents().resolve("main.json")));
        return spec.get("primaryModel").asText();
    }

    // --- happy path + chain update ------------------------------------------------------------------

    @Test
    void happyPath_storesKey_smokes_andSetsDefault() throws IOException {
        ProviderAddCommand command = command("fake", "echo-model");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command.run(scripted("sk-fake-123", "y"),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), command::smokeViaProvider);

        assertEquals(0, rc);
        assertEquals(Optional.of("sk-fake-123"), FileApiKeyStore.read(home.root(), "fake"));
        assertEquals("fake:echo-model", primaryModelOfMain());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Smoke test OK"));
    }

    @Test
    void emptyConfirmation_meansYes() throws IOException {
        ProviderAddCommand command = command("fake", "echo-model");
        int rc = command.run(scripted("sk", ""), sink(), sink(), ref -> "OK");
        assertEquals(0, rc);
        assertEquals("fake:echo-model", primaryModelOfMain(), "an empty [Y/n] line defaults to yes");
    }

    @Test
    void yesWordConfirmation_setsDefault() throws IOException {
        int rc = command("fake", "echo-model").run(scripted("sk", "yes"), sink(), sink(), ref -> "OK");
        assertEquals(0, rc);
        assertEquals("fake:echo-model", primaryModelOfMain());
    }

    @Test
    void declineDefault_storesKey_butLeavesMainUnchanged() throws IOException {
        ProviderAddCommand command = command("fake", "echo-model");
        int rc = command.run(scripted("sk-fake-123", "n"), sink(), sink(), command::smokeViaProvider);

        assertEquals(0, rc);
        assertEquals(Optional.of("sk-fake-123"), FileApiKeyStore.read(home.root(), "fake"));
        assertEquals("ollama:qwen3:1.7b", primaryModelOfMain(), "declining must not touch agents/main.json");
    }

    @Test
    void eofAtConfirmation_leavesMainUnchanged() throws IOException {
        // only the key is scripted; the confirm prompt reads null (EOF) -> no change
        int rc = command("fake", "echo-model").run(scripted("sk"), sink(), sink(), ref -> "OK");
        assertEquals(0, rc);
        assertEquals("ollama:qwen3:1.7b", primaryModelOfMain());
    }

    // --- model resolution ---------------------------------------------------------------------------

    @Test
    void emptyModelLine_usesTheSuggestedDefault() throws IOException {
        // anthropic has a suggested default; an empty model line accepts it. A stub smoker keeps it offline.
        ProviderAddCommand command = command("anthropic", null);
        int rc = command.run(scripted("sk-ant", "", "y"), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals(Optional.of("sk-ant"), FileApiKeyStore.read(home.root(), "anthropic"));
        assertEquals("anthropic:claude-3-5-haiku-latest", primaryModelOfMain());
    }

    @Test
    void typedModelLine_isUsedVerbatim() throws IOException {
        ProviderAddCommand command = command("openai", null);
        int rc = command.run(scripted("sk-oai", "  gpt-4.1-mini  ", "y"), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals("openai:gpt-4.1-mini", primaryModelOfMain(), "a typed model is stripped + used");
    }

    @Test
    void noModelAndNoSuggestion_aborts() {
        // the 'fake' provider has no DEFAULT_MODEL suggestion; an empty model line -> no model -> abort
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("fake", null).run(scripted("sk", ""),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");
        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no model id"));
    }

    // --- smoke failure + reply rendering ------------------------------------------------------------

    @Test
    void smokeFailure_storesKey_returns1_andLeavesMainUnchanged() throws IOException {
        ProviderAddCommand command = command("fake", "echo-model");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command.run(scripted("sk-fake", "y"), sink(), new PrintStream(err, true, StandardCharsets.UTF_8),
                ref -> {
                    throw new RuntimeException("transport boom", new IllegalStateException("401 Unauthorized"));
                });

        assertEquals(1, rc);
        assertEquals(Optional.of("sk-fake"), FileApiKeyStore.read(home.root(), "fake"),
                "the key is stored before the smoke so a re-run after fixing the cause works");
        assertEquals("ollama:qwen3:1.7b", primaryModelOfMain(), "a failed smoke must not change the default");
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("Smoke test FAILED"));
        assertTrue(errText.contains("401 Unauthorized"), "rootMessage walks to the deepest cause");
    }

    @Test
    void smokeFailureWithNoMessage_rendersTheExceptionType() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted("sk", "y"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8),
                ref -> {
                    throw new IllegalStateException();
                });
        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("IllegalStateException"));
    }

    @Test
    void nullReply_isRenderedAsNoReply() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted("sk", "n"),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), ref -> null);
        assertEquals(0, rc);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("(no reply)"));
    }

    @Test
    void longReply_isTruncated() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted("sk", "n"),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), ref -> "x".repeat(300));
        assertEquals(0, rc);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("..."), "an over-long reply is truncated");
    }

    // --- main.json edge cases -----------------------------------------------------------------------

    @Test
    void mainJsonAbsent_storesKeyButReportsInitHint() throws IOException {
        Files.deleteIfExists(home.agents().resolve("main.json"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted("sk", "y"),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals(Optional.of("sk"), FileApiKeyStore.read(home.root(), "fake"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("forvum init"));
    }

    @Test
    void mainJsonNotAnObject_reportsAndDoesNotCrash() throws IOException {
        Files.writeString(home.agents().resolve("main.json"), "[\"not\", \"an\", \"object\"]");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted("sk", "y"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(0, rc, "a malformed main.json is a warning, not a wizard failure (the key was stored)");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("not a JSON object"));
    }

    // --- validation + special providers -------------------------------------------------------------

    @Test
    void unknownProvider_failsWithoutWriting() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("bogus", "m").run(scripted("sk", "y"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown provider 'bogus'"));
        assertFalse(Files.exists(home.state().resolve("credentials").resolve("bogus")));
    }

    @Test
    void ollamaIsRejectedAsKeyless() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("ollama", "llama3.2").run(scripted("sk"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("keyless"));
        assertFalse(Files.exists(home.state().resolve("credentials").resolve("ollama")));
    }

    @Test
    void copilotIsRejectedWithLoginPointer() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("copilot", "gpt-4o").run(scripted("sk"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("forvum copilot login"));
    }

    @Test
    void emptyKey_aborts_withoutWriting() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command("fake", "echo-model").run(scripted(""),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no API key"));
        assertFalse(Files.exists(home.state().resolve("credentials").resolve("fake")));
    }

    @Test
    void blankProviderName_isUnknown() {
        int rc = command("   ", "m").run(scripted("sk", "y"), sink(), sink(), ref -> "OK");
        assertEquals(1, rc, "a blank provider name resolves to no installed provider");
    }

    // --- the ReaderPrompt (piped / non-TTY input path) ----------------------------------------------

    @Test
    void readerPromptReadsLinesInOrder() {
        ProviderAddCommand.ReaderPrompt prompt = new ProviderAddCommand.ReaderPrompt(
                new BufferedReader(new StringReader("the-secret\nthe-model\n")));
        assertEquals("the-secret", prompt.secret("API key: "));
        assertEquals("the-model", prompt.line("Model: "));
        assertNull(prompt.line("again"), "past EOF the reader prompt yields null");
    }

    public static class TempHomeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Path home = Path.of(System.getProperty("java.io.tmpdir"), "forvum-provideradd-test-home");
            return Map.of("forvum.home", home.toString());
        }
    }
}
