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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Flow tests for {@link OnboardCommand} (#194), driven through the package-private {@code run(...)}
 * with a scripted {@link ProviderAddCommand.Prompt} and a smoke seam — no live LLM, no stdin. The
 * {@code fake} provider on the app test classpath ({@link FakeModelProvider}) exercises the real key +
 * smoke path; {@code forvum.home} is a temp dir so writes never touch a real {@code ~/.forvum}. Covers
 * the quickstart and advanced interactive paths, the prompt-free {@code --non-interactive} flag mode,
 * and the idempotent keep/update re-run discipline.
 */
@QuarkusTest
@TestProfile(OnboardCommandTest.TempHomeProfile.class)
class OnboardCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ForvumHome home;

    @Inject
    Instance<ModelProvider> providers;

    @BeforeEach
    void cleanHome() throws IOException {
        Path root = home.root();
        if (Files.isDirectory(root)) {
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) {
                    if (!entry.equals(root)) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
        }
        Files.createDirectories(root);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private OnboardCommand command() {
        OnboardCommand command = new OnboardCommand();
        command.home = home;
        command.providers = providers;
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

    /** A prompt that fails the test if any prompt is issued — the non-interactive contract. */
    private static ProviderAddCommand.Prompt forbidden() {
        return new ProviderAddCommand.Prompt() {
            @Override
            public String line(String label) {
                fail("non-interactive mode must never prompt (line: " + label + ")");
                return null;
            }

            @Override
            public String secret(String label) {
                fail("non-interactive mode must never prompt (secret: " + label + ")");
                return null;
            }
        };
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private JsonNode mainSpec() throws IOException {
        return MAPPER.readTree(Files.readString(home.agents().resolve("main.json")));
    }

    // --- quickstart interactive path ------------------------------------------------------------------

    @Test
    void quickstart_configuresProviderBeltChannelAndIdentity() throws IOException {
        OnboardCommand command = command();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // mode (Enter = quickstart), provider, model, API key — quickstart asks nothing else.
        int rc = command.run(scripted("", "fake", "echo-model", "sk-onboard-1"),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), command::smokeViaProvider);

        assertEquals(0, rc);
        assertEquals(Optional.of("sk-onboard-1"), FileApiKeyStore.read(home.root(), "fake"));
        JsonNode spec = mainSpec();
        assertEquals("fake:echo-model", spec.get("primaryModel").asText());
        assertEquals(InitCommand.DEFAULT_IDENTITY_ID, spec.get("identityId").asText());
        List<String> belt = new java.util.ArrayList<>();
        spec.get("allowedTools").forEach(tool -> belt.add(tool.asText()));
        assertEquals(InitCommand.DEFAULT_ALLOWED_TOOLS, belt, "quickstart enables the default belt");
        assertTrue(Files.isRegularFile(home.agents().resolve("main.md")));
        assertTrue(Files.isRegularFile(home.identities().resolve("default.json")));
        assertEquals("{\n  \"enabled\": true\n}\n",
                Files.readString(home.channels().resolve("tui.json")), "TUI is enabled by default");
        assertFalse(Files.exists(home.root().resolve("tools").resolve("web.json")),
                "quickstart keeps the keyless DuckDuckGo default (no file needed)");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Smoke test OK"),
                "the captured key is validated with provider add's smoke chat");
    }

    @Test
    void quickstart_eofEverywhere_neverBlocks_andDefaultsToOllama() throws IOException {
        // A piped run with no input: every prompt reads EOF -> quickstart, ollama, default model, no key.
        OnboardCommand command = command();
        int rc = command.run(scripted(), sink(), sink(),
                ref -> fail("no key captured -> no smoke must run"));

        assertEquals(0, rc);
        assertEquals("ollama:gemma4:31b-cloud", mainSpec().get("primaryModel").asText());
        assertTrue(Files.isRegularFile(home.channels().resolve("tui.json")));
    }

    @Test
    void unknownProvider_failsWithoutWriting() {
        OnboardCommand command = command();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command.run(scripted("", "bogus"), sink(),
                new PrintStream(err, true, StandardCharsets.UTF_8), ref -> "OK");

        assertEquals(1, rc);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown provider 'bogus'"));
        assertFalse(Files.exists(home.agents().resolve("main.json")), "nothing is written on abort");
    }

    @Test
    void copilot_pointsAtDeviceLogin_andSetsModel() throws IOException {
        OnboardCommand command = command();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command.run(scripted("", "copilot", ""),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(),
                ref -> fail("copilot must not smoke here"));

        assertEquals(0, rc);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("forvum copilot login"));
        assertEquals("copilot:gpt-4o", mainSpec().get("primaryModel").asText());
    }

    @Test
    void smokeFailure_keepsKeyAndConfig_butReturns1() throws IOException {
        OnboardCommand command = command();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = command.run(scripted("", "fake", "echo-model", "sk-bad"),
                sink(), new PrintStream(err, true, StandardCharsets.UTF_8),
                ref -> {
                    throw new RuntimeException("transport boom", new IllegalStateException("401 Unauthorized"));
                });

        assertEquals(1, rc);
        assertEquals(Optional.of("sk-bad"), FileApiKeyStore.read(home.root(), "fake"),
                "the key stays stored so a re-run after fixing the cause works");
        assertEquals("fake:echo-model", mainSpec().get("primaryModel").asText(),
                "the config is still written so the setup is resumable");
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("Smoke test FAILED"));
        assertTrue(errText.contains("401 Unauthorized"), "rootMessage walks to the deepest cause");
    }

    // --- advanced interactive path ----------------------------------------------------------------------

    @Test
    void advanced_configuresBeltSearchChannelsAndPersona() throws IOException {
        OnboardCommand command = command();
        int rc = command.run(scripted(
                        "a",              // mode: advanced
                        "fake",           // provider
                        "echo-model",     // model
                        "sk-adv",         // API key
                        "y",              // enable default belt
                        "b",              // web search backend: brave
                        "brave-key-1",    // brave API key
                        "y",              // enable TUI
                        "y",              // enable Telegram
                        "tg-token-1",     // telegram bot token
                        "Ada",            // agent name
                        "You are Ada."),  // system prompt
                sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals("fake:echo-model", mainSpec().get("primaryModel").asText());
        assertEquals("You are Ada.\n", Files.readString(home.agents().resolve("main.md")));

        JsonNode telegram = MAPPER.readTree(Files.readString(home.channels().resolve("telegram.json")));
        assertTrue(telegram.get("enabled").asBoolean());
        assertEquals("tg-token-1", telegram.get("botToken").asText());

        JsonNode web = MAPPER.readTree(Files.readString(home.root().resolve("tools").resolve("web.json")));
        assertEquals("brave", web.get("backend").asText());
        assertEquals("brave-key-1", web.get("braveApiKey").asText());
    }

    @Test
    void advanced_decliningBeltAndTui_writesEmptyBeltAndNoTuiFile() throws IOException {
        OnboardCommand command = command();
        int rc = command.run(scripted(
                        "advanced", "fake", "echo-model", "", // skip the key
                        "n",              // decline the belt
                        "d",              // duckduckgo backend (explicit)
                        "n",              // decline TUI
                        "n",              // decline Telegram
                        "", ""),          // skip name + prompt
                sink(), sink(), ref -> fail("no key -> no smoke"));

        assertEquals(0, rc);
        assertEquals(0, mainSpec().get("allowedTools").size(), "a declined belt is empty");
        assertFalse(Files.exists(home.channels().resolve("tui.json")), "a declined TUI writes no file");
        JsonNode web = MAPPER.readTree(Files.readString(home.root().resolve("tools").resolve("web.json")));
        assertEquals("duckduckgo", web.get("backend").asText());
        assertFalse(web.has("braveApiKey"));
    }

    @Test
    void advanced_braveWithoutKey_fallsBackToKeylessDefault() throws IOException {
        OnboardCommand command = command();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = command.run(scripted(
                        "a", "fake", "echo-model", "",
                        "y",    // belt
                        "b",    // brave...
                        "",     // ...but no key
                        "y", "n", "", ""),
                new PrintStream(out, true, StandardCharsets.UTF_8), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertFalse(Files.exists(home.root().resolve("tools").resolve("web.json")),
                "brave without a key keeps the keyless default and writes nothing");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("No Brave key captured"));
    }

    // --- non-interactive flag mode -----------------------------------------------------------------------

    @Test
    void nonInteractive_flagsConfigureEverything_withNoPrompts() throws IOException {
        OnboardCommand command = command();
        command.nonInteractive = true;
        command.provider = "fake";
        command.model = "flag-model";
        command.apiKey = "sk-ci";
        command.braveKey = "brave-ci";
        command.telegramToken = "tg-ci";
        command.agentName = "CI";

        int rc = command.run(forbidden(), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals(Optional.of("sk-ci"), FileApiKeyStore.read(home.root(), "fake"));
        JsonNode spec = mainSpec();
        assertEquals("fake:flag-model", spec.get("primaryModel").asText());
        assertEquals(InitCommand.DEFAULT_ALLOWED_TOOLS.size(), spec.get("allowedTools").size());
        assertTrue(Files.readString(home.agents().resolve("main.md")).contains("You are CI"));
        assertTrue(Files.isRegularFile(home.channels().resolve("tui.json")));
        JsonNode telegram = MAPPER.readTree(Files.readString(home.channels().resolve("telegram.json")));
        assertEquals("tg-ci", telegram.get("botToken").asText());
        JsonNode web = MAPPER.readTree(Files.readString(home.root().resolve("tools").resolve("web.json")));
        assertEquals("brave", web.get("backend").asText(), "--brave-key implies the brave backend");
    }

    @Test
    void nonInteractive_defaults_areQuickstartOllama() throws IOException {
        OnboardCommand command = command();
        command.nonInteractive = true;

        int rc = command.run(forbidden(), sink(), sink(), ref -> fail("keyless ollama must not smoke"));

        assertEquals(0, rc);
        assertEquals("ollama:gemma4:31b-cloud", mainSpec().get("primaryModel").asText());
        assertTrue(Files.isRegularFile(home.channels().resolve("tui.json")));
    }

    @Test
    void nonInteractive_noTuiAndNoBelt_flagsAreHonored() throws IOException {
        OnboardCommand command = command();
        command.nonInteractive = true;
        command.noTui = true;
        command.noDefaultBelt = true;

        int rc = command.run(forbidden(), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals(0, mainSpec().get("allowedTools").size());
        assertFalse(Files.exists(home.channels().resolve("tui.json")));
    }

    // --- idempotency: keep/update discipline ---------------------------------------------------------------

    @Test
    void nonInteractiveRerun_keepsOperatorEditsByDefault() throws IOException {
        Files.createDirectories(home.agents());
        Files.writeString(home.agents().resolve("main.json"),
                "{\n  \"primaryModel\": \"anthropic:opus\",\n  \"custom\": 42\n}\n");
        Files.writeString(home.agents().resolve("main.md"), "Operator persona.\n");

        OnboardCommand command = command();
        command.nonInteractive = true;
        command.provider = "fake";
        command.model = "echo-model";
        command.apiKey = "sk-rerun";
        command.agentName = "New";

        int rc = command.run(forbidden(), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals("anthropic:opus", mainSpec().get("primaryModel").asText(),
                "a re-run without --update never clobbers an operator's main.json");
        assertEquals("Operator persona.\n", Files.readString(home.agents().resolve("main.md")));
        assertTrue(Files.isRegularFile(home.channels().resolve("tui.json")),
                "an absent file is still written on a re-run (resumable)");
    }

    @Test
    void nonInteractiveRerun_withUpdate_updatesButPreservesUnknownFields() throws IOException {
        Files.createDirectories(home.agents());
        Files.writeString(home.agents().resolve("main.json"),
                "{\n  \"primaryModel\": \"anthropic:opus\",\n  \"custom\": 42\n}\n");

        OnboardCommand command = command();
        command.nonInteractive = true;
        command.update = true;
        command.provider = "fake";
        command.model = "echo-model";
        command.apiKey = "sk-upd";

        int rc = command.run(forbidden(), sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        JsonNode spec = mainSpec();
        assertEquals("fake:echo-model", spec.get("primaryModel").asText());
        assertEquals(42, spec.get("custom").asInt(), "unknown fields survive an update");
        assertEquals(InitCommand.DEFAULT_IDENTITY_ID, spec.get("identityId").asText());
    }

    @Test
    void interactiveRerun_asksPerFile_defaultIsKeep() throws IOException {
        Files.createDirectories(home.agents());
        Files.writeString(home.agents().resolve("main.json"),
                "{\n  \"primaryModel\": \"anthropic:opus\"\n}\n");

        OnboardCommand command = command();
        // mode, provider, model, key, then the main.json keep/update question answered "n" (and EOF after).
        int rc = command.run(scripted("", "fake", "echo-model", "sk-keep", "n"),
                sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        assertEquals("anthropic:opus", mainSpec().get("primaryModel").asText(),
                "answering 'n' (and the [y/N] default) keeps the operator's file");
    }

    @Test
    void interactiveRerun_updateAnswerRewritesTheFile() throws IOException {
        Files.createDirectories(home.agents());
        Files.writeString(home.agents().resolve("main.json"),
                "{\n  \"primaryModel\": \"anthropic:opus\",\n  \"custom\": 7\n}\n");

        OnboardCommand command = command();
        int rc = command.run(scripted("", "fake", "echo-model", "sk-upd2", "y"),
                sink(), sink(), ref -> "OK");

        assertEquals(0, rc);
        JsonNode spec = mainSpec();
        assertEquals("fake:echo-model", spec.get("primaryModel").asText());
        assertEquals(7, spec.get("custom").asInt());
        List<String> belt = new java.util.ArrayList<>();
        spec.get("allowedTools").forEach(tool -> belt.add(tool.asText()));
        assertEquals(InitCommand.DEFAULT_ALLOWED_TOOLS, belt, "an approved update also wires the belt");
    }

    @Test
    void rerunAfterQuickstart_isIdempotent() throws IOException {
        OnboardCommand first = command();
        first.nonInteractive = true;
        first.provider = "fake";
        first.model = "echo-model";
        first.apiKey = "sk-1";
        assertEquals(0, first.run(forbidden(), sink(), sink(), ref -> "OK"));
        String mainJson = Files.readString(home.agents().resolve("main.json"));
        String mainMd = Files.readString(home.agents().resolve("main.md"));

        OnboardCommand second = command();
        second.nonInteractive = true;
        second.provider = "fake";
        second.model = "other-model";
        second.apiKey = "sk-2";
        assertEquals(0, second.run(forbidden(), sink(), sink(), ref -> "OK"));

        assertEquals(mainJson, Files.readString(home.agents().resolve("main.json")),
                "a re-run without --update leaves every existing file byte-identical");
        assertEquals(mainMd, Files.readString(home.agents().resolve("main.md")));
    }

    // --- the first-run hook's decision (RootCommand) -----------------------------------------------------

    @Test
    void onboardingOffer_acceptsEnterYesAndY_declinesEofAndNo() {
        assertTrue(RootCommand.acceptsOnboarding(""));
        assertTrue(RootCommand.acceptsOnboarding("  "));
        assertTrue(RootCommand.acceptsOnboarding("y"));
        assertTrue(RootCommand.acceptsOnboarding("YES"));
        assertFalse(RootCommand.acceptsOnboarding(null), "EOF must never launch the wizard");
        assertFalse(RootCommand.acceptsOnboarding("n"));
        assertFalse(RootCommand.acceptsOnboarding("whatever"));
    }

    public static class TempHomeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Path home = Path.of(System.getProperty("java.io.tmpdir"), "forvum-onboard-test-home");
            return Map.of("forvum.home", home.toString());
        }
    }
}
