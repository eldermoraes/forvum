package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.sdk.FileApiKeyStore;
import ai.forvum.sdk.ModelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code forvum provider add <provider> [--model <model>]} (P2-10 #35): the interactive provider-onboarding
 * wizard. It prompts for the provider's API key (no echo), writes it owner-only ({@code 0600}) to
 * {@code ~/.forvum/state/credentials/<provider>} via {@link FileApiKeyStore} (the {@code CopilotCredentials}
 * recipe generalized — secrets in a 0600 file, OpenClaw parity, NOT a native keychain), runs a DB-free
 * smoke chat against the provider to prove the key works, and offers to make {@code <provider>:<model>}
 * agent {@code main}'s default model (editing {@code agents/main.json}, preserving unknown fields).
 *
 * <p>It is a {@code CommandMode} one-shot (like {@code copilot login}): it only writes a credential file and
 * runs a direct provider chat — it touches neither the DB nor the watcher. Ollama is keyless (configure its
 * base URL) and Copilot uses {@code forvum copilot login}, so both are rejected with a pointer; every other
 * installed key-based provider (anthropic / openai / google) is supported.
 *
 * <p>The key-based providers read this stored key only when their {@code @ConfigProperty} api-key is blank,
 * so an operator-exported {@code QUARKUS_LANGCHAIN4J_*_API_KEY} keeps precedence. The smoke chat resolves the
 * provider directly (the raw {@link ModelProvider}, not the ledgering {@code LlmSelector}) so no
 * {@code provider_calls} row is written for it.
 *
 * <p>Input goes through a {@link Prompt} seam so the flow is testable and {@code Console}/{@code Reader}
 * never mix on one stdin: a TTY uses {@link ConsolePrompt} (the {@code Console} echoes the label and masks
 * the secret), a piped / non-TTY run uses {@link ReaderPrompt}.
 */
@CommandLine.Command(
        name = "add",
        description = "Store an LLM provider's API key (0600), smoke-test it, and optionally set it as "
                + "agent 'main's default model.")
public class ProviderAddCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SMOKE_PROMPT = "Reply with the single word: OK";

    /** Providers handled elsewhere: Ollama is keyless; Copilot uses device-code login. */
    private static final Map<String, String> SPECIAL = Map.of(
            "ollama", "Ollama is keyless — set quarkus.langchain4j.ollama.base-url (no API key needed).",
            "copilot", "Copilot uses device-code auth — run `forvum copilot login` instead.");

    /** Suggested smoke/default model per provider (editable at the prompt). */
    private static final Map<String, String> DEFAULT_MODEL = Map.of(
            "anthropic", "claude-3-5-haiku-latest",
            "openai", "gpt-4o-mini",
            "google", "gemini-2.0-flash");

    @Inject
    ForvumHome home;

    @Inject
    Instance<ModelProvider> providers;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<provider>",
            description = "Provider id to add: anthropic, openai, or google.")
    String providerName;

    @CommandLine.Option(
            names = "--model",
            description = "Model id for the smoke test and the default chain (prompted if omitted).")
    String model;

    @Override
    public Integer call() {
        Console console = System.console();
        Prompt prompt = console != null && console.isTerminal()
                ? new ConsolePrompt(console)
                : new ReaderPrompt(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        return run(prompt, System.out, System.err, this::smokeViaProvider);
    }

    /** The flow with an injectable prompt + smoke seam so a test drives it offline. Package-private. */
    int run(Prompt prompt, PrintStream out, PrintStream err, Smoker smoker) {
        String id = providerName == null ? "" : providerName.strip().toLowerCase(Locale.ROOT);
        if (SPECIAL.containsKey(id)) {
            err.println("provider add: " + SPECIAL.get(id));
            return 1;
        }
        Set<String> installed = installedProviderIds();
        if (!installed.contains(id)) {
            err.println("provider add: unknown provider '" + providerName + "'. Supported: "
                    + String.join(", ", supportedIds(installed)) + ".");
            return 1;
        }

        String key = prompt.secret("API key for " + id + ": ");
        if (key == null || key.isBlank()) {
            err.println("provider add: no API key entered; aborting (nothing was written).");
            return 1;
        }

        String modelId = (model != null && !model.isBlank()) ? model.strip() : resolveModel(prompt, id);
        if (modelId == null || modelId.isBlank()) {
            err.println("provider add: no model id entered; aborting (nothing was written).");
            return 1;
        }
        // id is an installed (non-blank) provider and modelId is non-blank and stripped, so
        // "<id>:<modelId>" always parses — no try/catch needed (ModelRef splits on the first colon).
        ModelRef ref = ModelRef.parse(id + ":" + modelId);

        FileApiKeyStore.store(home.root(), id, key.strip());
        out.println("Stored " + id + " API key (0600) at "
                + FileApiKeyStore.credentialFile(home.root(), id) + ".");

        out.println("Running a smoke test against " + ref + " ...");
        try {
            out.println("Smoke test OK: " + oneLine(smoker.smoke(ref)));
        } catch (Exception e) {
            err.println("Smoke test FAILED for " + ref + ": " + rootMessage(e));
            err.println("The key is stored; fix the cause (key, model id, or network) and re-run "
                    + "`forvum provider add " + id + " --model " + modelId + "`.");
            return 1;
        }

        if (confirmDefault(prompt, ref)) {
            updateMainPrimaryModel(ref, out, err);
        } else {
            out.println("Left agent 'main's model unchanged. Reference " + ref + " in an agent spec to use it.");
        }
        return 0;
    }

    /** The smoke step, abstracted so a test can supply a deterministic (or failing) substitute. */
    @FunctionalInterface
    interface Smoker {
        String smoke(ModelRef ref) throws Exception;
    }

    /** Line input, so the flow is testable and {@code Console}/{@code Reader} never mix on one stdin. */
    interface Prompt {
        /** A visible line (model id, confirmation); {@code null} at EOF. */
        String line(String label);

        /** A secret line (API key), masked where possible; {@code null} at EOF. */
        String secret(String label);
    }

    /** Production TTY prompt: the {@link Console} echoes the label and masks the secret. */
    static final class ConsolePrompt implements Prompt {
        private final Console console;

        ConsolePrompt(Console console) {
            this.console = console;
        }

        @Override
        public String line(String label) {
            return console.readLine("%s", label);
        }

        @Override
        public String secret(String label) {
            char[] secret = console.readPassword("%s", label);
            return secret == null ? null : new String(secret);
        }
    }

    /** Piped / non-TTY prompt: read echoed from the reader (no label printed). */
    static final class ReaderPrompt implements Prompt {
        private final BufferedReader reader;

        ReaderPrompt(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public String line(String label) {
            return read();
        }

        @Override
        public String secret(String label) {
            return read();
        }

        private String read() {
            try {
                return reader.readLine();
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Resolve the provider directly (no ledger) and run one chat — the production smoke. */
    String smokeViaProvider(ModelRef ref) {
        return providerFor(ref.provider()).resolve(ref).chat(SMOKE_PROMPT);
    }

    private String resolveModel(Prompt prompt, String id) {
        String suggestion = DEFAULT_MODEL.get(id);
        String line = prompt.line("Model id" + (suggestion != null ? " [" + suggestion + "]" : "") + ": ");
        return (line == null || line.isBlank()) ? suggestion : line.strip();
    }

    private static boolean confirmDefault(Prompt prompt, ModelRef ref) {
        String answer = prompt.line("Make " + ref + " the default model for agent 'main'? [Y/n] ");
        if (answer == null) {
            return false; // EOF / no input: never change config unprompted
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("y") || normalized.equals("yes");
    }

    private Set<String> installedProviderIds() {
        Set<String> ids = new TreeSet<>();
        for (ModelProvider provider : providers) {
            ids.add(provider.extensionId());
        }
        return ids;
    }

    private static Set<String> supportedIds(Set<String> installed) {
        Set<String> supported = new TreeSet<>(installed);
        supported.removeAll(SPECIAL.keySet());
        return supported;
    }

    private ModelProvider providerFor(String id) {
        for (ModelProvider provider : providers) {
            if (provider.extensionId().equals(id)) {
                return provider;
            }
        }
        throw new IllegalStateException("No model provider for '" + id + "' on the classpath.");
    }

    private void updateMainPrimaryModel(ModelRef ref, PrintStream out, PrintStream err) {
        Path mainJson = home.agents().resolve("main.json");
        if (!Files.isRegularFile(mainJson)) {
            out.println("No agents/main.json yet — run `forvum init` to scaffold it. The key is stored; "
                    + "the default model was not changed.");
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(mainJson));
            if (node == null || !node.isObject()) {
                err.println("agents/main.json is not a JSON object; the default model was not changed.");
                return;
            }
            ObjectNode spec = (ObjectNode) node;
            spec.put("primaryModel", ref.toString());
            Files.writeString(mainJson, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
            out.println("Set agent 'main' primaryModel = " + ref + ".");
        } catch (IOException e) {
            err.println("Could not update agents/main.json: " + e.getMessage());
        }
    }

    private static String oneLine(String reply) {
        if (reply == null) {
            return "(no reply)";
        }
        String flat = reply.strip().replaceAll("\\s+", " ");
        return flat.length() > 120 ? flat.substring(0, 117) + "..." : flat;
    }

    /** The deepest cause's "Type: message", hop-capped against a cyclic cause chain. */
    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        for (int i = 0; i < 20 && cause.getCause() != null && cause.getCause() != cause; i++) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null
                ? cause.getClass().getSimpleName() + ": " + message
                : cause.getClass().getSimpleName();
    }
}
