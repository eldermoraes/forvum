package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.sdk.FileApiKeyStore;
import ai.forvum.sdk.ModelProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code forvum onboard} (#194): the interactive first-run onboarding wizard. It ORCHESTRATES the
 * existing machinery — {@link FileApiKeyStore} + {@code provider add}'s ledger-free smoke chat for the
 * key, {@code forvum init}'s file shapes for {@code agents/main.json}/{@code agents/main.md}/
 * {@code identities/default.json}/{@code channels/*.json}, and the {@code tools/web.json} shape
 * {@code WebToolConfig} reads — into one guided flow: provider + model (key captured, stored 0600, and
 * validated), the safe read-only default tool belt, an optional web-search backend, channel enablement
 * (TUI default-on; Telegram with a token), and an optional agent name + system prompt.
 *
 * <p>Two interactive paths: <em>quickstart</em> (the default — provider/model/key only, everything else
 * takes the sensible default) and <em>advanced</em> ({@code --advanced} or answering {@code a} at the
 * mode prompt — belt, web search, channels, and persona are each asked). A third, prompt-free path is
 * {@code --non-interactive}: every choice comes from flags with quickstart defaults, for CI/Docker.
 *
 * <p>Idempotent + resumable: an absent file is written; an existing file is NEVER clobbered silently —
 * interactively the wizard asks keep/update per file (default keep), non-interactively it keeps unless
 * {@code --update} is passed. Ollama is keyless (base-url pointer, no key prompt) and Copilot points at
 * {@code forvum copilot login}; every other installed provider goes through the key + smoke path.
 *
 * <p>Like {@code provider add} it is a CLI one-shot: input goes through the shared
 * {@link ProviderAddCommand.Prompt} seam (TTY {@code Console} masks the key; piped input reads lines;
 * EOF always resolves to the default so a piped run never blocks), and the flag mode reads no stdin at
 * all — native-safe, no new reflection surface (all JSON goes through the existing Jackson tree API).
 */
@CommandLine.Command(
        name = "onboard",
        description = "Interactive first-run wizard: provider + model (key validated), tool belt, "
                + "web search, channels, and persona. Use --non-interactive for a prompt-free CI/Docker setup.")
public class OnboardCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SMOKE_PROMPT = "Reply with the single word: OK";
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    /** Keyless / login-based providers: no API-key prompt, just a pointer. */
    private static final Map<String, String> KEYLESS_HINT = Map.of(
            "ollama", "Ollama is keyless - it uses the local server at "
                    + "quarkus.langchain4j.ollama.base-url (default http://localhost:11434).",
            "copilot", "Copilot uses device-code auth - run `forvum copilot login` to authenticate.");

    /** Suggested default model per provider (editable at the prompt). */
    private static final Map<String, String> DEFAULT_MODEL = Map.of(
            "ollama", "gemma4:31b-cloud",
            "anthropic", "claude-3-5-haiku-latest",
            "openai", "gpt-4o-mini",
            "google", "gemini-2.0-flash",
            "copilot", "gpt-4o");

    static final String DEFAULT_PROVIDER = "ollama";
    static final String DEFAULT_PERSONA =
            "You are Forvum's main assistant. Be concise, accurate, and helpful.\n";

    @Inject
    ForvumHome home;

    @Inject
    Instance<ModelProvider> providers;

    @CommandLine.Option(names = "--non-interactive",
            description = "No prompts: configure everything from flags with quickstart defaults (CI/Docker).")
    boolean nonInteractive;

    @CommandLine.Option(names = "--advanced",
            description = "Interactive advanced path: ask about the belt, web search, channels, and persona.")
    boolean advanced;

    @CommandLine.Option(names = "--provider",
            description = "Model provider: ollama (default), anthropic, openai, google, or copilot.")
    String provider;

    @CommandLine.Option(names = "--model", description = "Model id (provider-specific default if omitted).")
    String model;

    @CommandLine.Option(names = "--api-key",
            description = "API key for a key-based provider (stored 0600 and smoke-tested).")
    String apiKey;

    @CommandLine.Option(names = "--no-default-belt",
            description = "Do not enable the safe read-only default tool belt.")
    boolean noDefaultBelt;

    @CommandLine.Option(names = "--search-backend",
            description = "Web search backend for tools/web.json: duckduckgo (keyless) or brave.")
    String searchBackend;

    @CommandLine.Option(names = "--brave-key",
            description = "Brave Search API key (implies --search-backend brave).")
    String braveKey;

    @CommandLine.Option(names = "--no-tui", description = "Do not enable the TUI channel.")
    boolean noTui;

    @CommandLine.Option(names = "--telegram-token",
            description = "Telegram bot token: enables channels/telegram.json.")
    String telegramToken;

    @CommandLine.Option(names = "--agent-name", description = "Optional agent name for the persona.")
    String agentName;

    @CommandLine.Option(names = "--system-prompt",
            description = "Optional system prompt for agents/main.md.")
    String systemPrompt;

    @CommandLine.Option(names = "--update",
            description = "Non-interactive mode only: update existing config files (default is keep).")
    boolean update;

    @Override
    public Integer call() {
        ProviderAddCommand.Prompt prompt;
        if (nonInteractive) {
            prompt = new NoPrompt();
        } else {
            Console console = System.console();
            prompt = console != null && console.isTerminal()
                    ? new ProviderAddCommand.ConsolePrompt(console)
                    : new ProviderAddCommand.ReaderPrompt(
                            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }
        return run(prompt, System.out, System.err, this::smokeViaProvider);
    }

    /** The flow with an injectable prompt + smoke seam so a test drives it offline. Package-private. */
    int run(ProviderAddCommand.Prompt prompt, PrintStream out, PrintStream err,
            ProviderAddCommand.Smoker smoker) {
        boolean interactive = !nonInteractive;
        out.println("Forvum onboarding - this wizard writes config files under " + home.root() + ".");

        boolean advancedPath = advanced;
        if (interactive && !advanced) {
            String mode = prompt.line("Setup mode - [Q]uickstart or [a]dvanced: ");
            advancedPath = mode != null && mode.strip().toLowerCase(Locale.ROOT).startsWith("a");
        }

        // --- provider + model -----------------------------------------------------------------------
        Set<String> installed = installedProviderIds();
        String providerId = normalized(provider);
        if (providerId.isEmpty() && interactive) {
            String line = prompt.line("Model provider [" + String.join(", ", installed)
                    + "] (" + DEFAULT_PROVIDER + "): ");
            providerId = normalized(line);
        }
        if (providerId.isEmpty()) {
            providerId = DEFAULT_PROVIDER;
        }
        if (!installed.contains(providerId)) {
            err.println("onboard: unknown provider '" + providerId + "'. Supported: "
                    + String.join(", ", installed) + ". Nothing was written.");
            return 1;
        }

        String modelId = model != null && !model.isBlank() ? model.strip() : null;
        String suggestion = DEFAULT_MODEL.get(providerId);
        if (modelId == null && interactive) {
            String line = prompt.line("Model id" + (suggestion != null ? " [" + suggestion + "]" : "") + ": ");
            modelId = line == null || line.isBlank() ? null : line.strip();
        }
        if (modelId == null) {
            modelId = suggestion;
        }
        if (modelId == null || modelId.isBlank()) {
            err.println("onboard: no model id for provider '" + providerId
                    + "' and no default is known. Nothing was written.");
            return 1;
        }
        ModelRef ref = ModelRef.parse(providerId + ":" + modelId);

        // --- API key: capture, store 0600, and smoke-validate (provider add's machinery) --------------
        boolean smokeFailed = false;
        if (KEYLESS_HINT.containsKey(providerId)) {
            out.println(KEYLESS_HINT.get(providerId));
        } else {
            String key = apiKey != null && !apiKey.isBlank() ? apiKey.strip() : null;
            if (key == null && interactive) {
                String secret = prompt.secret("API key for " + providerId + " (Enter to skip): ");
                key = secret == null || secret.isBlank() ? null : secret.strip();
            }
            if (key != null) {
                FileApiKeyStore.store(home.root(), providerId, key);
                out.println("Stored " + providerId + " API key (0600) at "
                        + FileApiKeyStore.credentialFile(home.root(), providerId) + ".");
                out.println("Running a smoke test against " + ref + " ...");
                try {
                    out.println("Smoke test OK: " + oneLine(smoker.smoke(ref)));
                } catch (Exception e) {
                    smokeFailed = true;
                    err.println("Smoke test FAILED for " + ref + ": " + rootMessage(e));
                    err.println("The key is stored; fix the cause (key, model id, or network) and re-run "
                            + "`forvum provider add " + providerId + " --model " + modelId + "`.");
                }
            } else {
                out.println("No API key captured for " + providerId + " - run `forvum provider add "
                        + providerId + "` later to store and validate one.");
            }
        }

        // --- tool belt --------------------------------------------------------------------------------
        boolean belt = !noDefaultBelt;
        if (interactive && advancedPath && !noDefaultBelt) {
            belt = askYesNo(prompt, "Enable the safe read-only default tool belt ("
                    + String.join(", ", InitCommand.DEFAULT_ALLOWED_TOOLS)
                    + ")? shell.exec, sandbox.run, and browser.* stay opt-in "
                    + "(edit agents/main.json to add them). [Y/n] ", true);
        }

        // --- web search -------------------------------------------------------------------------------
        String backend = normalized(searchBackend);
        String braveApiKey = braveKey != null && !braveKey.isBlank() ? braveKey.strip() : null;
        if (braveApiKey != null && backend.isEmpty()) {
            backend = "brave";
        }
        if (interactive && advancedPath && backend.isEmpty() && braveApiKey == null) {
            String line = prompt.line(
                    "Web search backend - [d]uckduckgo (keyless, default) or [b]rave (needs an API key): ");
            String choice = normalized(line);
            if (choice.startsWith("b")) {
                backend = "brave";
                String secret = prompt.secret("Brave Search API key (Enter to skip): ");
                braveApiKey = secret == null || secret.isBlank() ? null : secret.strip();
            } else if (choice.startsWith("d")) {
                backend = "duckduckgo";
            }
        }
        if (backend.equals("brave") && braveApiKey == null) {
            out.println("No Brave key captured - keeping the keyless DuckDuckGo default "
                    + "(set braveApiKey in tools/web.json later).");
            backend = "";
        }

        // --- channels ---------------------------------------------------------------------------------
        boolean tui = !noTui;
        if (interactive && advancedPath && !noTui) {
            tui = askYesNo(prompt, "Enable the TUI channel (interactive terminal sessions)? [Y/n] ", true);
        }
        String tgToken = telegramToken != null && !telegramToken.isBlank() ? telegramToken.strip() : null;
        if (interactive && advancedPath && tgToken == null
                && askYesNo(prompt, "Enable the Telegram channel? [y/N] ", false)) {
            String secret = prompt.secret("Telegram bot token: ");
            tgToken = secret == null || secret.isBlank() ? null : secret.strip();
        }

        // --- identity / persona -----------------------------------------------------------------------
        String name = agentName != null && !agentName.isBlank() ? agentName.strip() : null;
        String persona = systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt.strip() : null;
        if (interactive && advancedPath) {
            if (name == null) {
                String line = prompt.line("Agent name (Enter to skip): ");
                name = line == null || line.isBlank() ? null : line.strip();
            }
            if (persona == null) {
                String line = prompt.line("System prompt (Enter for the default): ");
                persona = line == null || line.isBlank() ? null : line.strip();
            }
        }

        // --- write the config surface (idempotent: keep/update per existing file) ---------------------
        try {
            writePersona(prompt, out, interactive, name, persona);
            writeMainSpec(prompt, out, err, interactive, ref, belt);
            writeIfAbsent(home.identities().resolve("default.json"),
                    "{\n  \"channelAccounts\": {}\n}\n", out);
            if (tui) {
                writeIfAbsent(home.channels().resolve("tui.json"), "{\n  \"enabled\": true\n}\n", out);
            }
            if (tgToken != null) {
                writeJson(prompt, out, interactive, home.channels().resolve("telegram.json"),
                        telegramSpec(tgToken));
            }
            if (!backend.isEmpty()) {
                writeJson(prompt, out, interactive, home.root().resolve("tools").resolve("web.json"),
                        webSpec(backend, braveApiKey));
            }
        } catch (IOException e) {
            err.println("onboard: could not write configuration: " + e.getMessage());
            return 1;
        }

        out.println("Onboarding " + (smokeFailed ? "finished with a FAILED key smoke test" : "complete")
                + ". Model: " + ref + ". Run `forvum` to start"
                + (tui ? " an interactive session." : "."));
        return smokeFailed ? 1 : 0;
    }

    // --- config writers (init's shapes + writeIfAbsent discipline) ------------------------------------

    private void writePersona(ProviderAddCommand.Prompt prompt, PrintStream out, boolean interactive,
                              String name, String persona) throws IOException {
        Path file = home.agents().resolve("main.md");
        String content;
        if (persona != null) {
            content = persona.endsWith("\n") ? persona : persona + "\n";
        } else if (name != null) {
            content = "You are " + name + ", Forvum's main assistant. Be concise, accurate, and helpful.\n";
        } else {
            content = DEFAULT_PERSONA;
        }
        boolean customized = persona != null || name != null;
        if (Files.exists(file)) {
            // Only a customized persona is worth an update question; the default never clobbers an edit.
            if (customized && decideUpdate(prompt, interactive, file)) {
                writeOwnerOnly(file, content);
                out.println("Updated " + relative(file) + ".");
            } else {
                out.println("Kept existing " + relative(file) + ".");
            }
            return;
        }
        writeOwnerOnly(file, content);
        out.println("Wrote " + relative(file) + ".");
    }

    private void writeMainSpec(ProviderAddCommand.Prompt prompt, PrintStream out, PrintStream err,
                               boolean interactive, ModelRef ref, boolean belt) throws IOException {
        Path file = home.agents().resolve("main.json");
        if (!Files.exists(file)) {
            ObjectNode spec = MAPPER.createObjectNode();
            spec.put("primaryModel", ref.toString());
            spec.put("identityId", InitCommand.DEFAULT_IDENTITY_ID);
            ArrayNode tools = spec.putArray("allowedTools");
            if (belt) {
                InitCommand.DEFAULT_ALLOWED_TOOLS.forEach(tools::add);
            }
            writeOwnerOnly(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n");
            out.println("Wrote " + relative(file) + " (primaryModel " + ref
                    + (belt ? ", default tool belt" : ", empty tool belt") + ").");
            return;
        }
        if (!decideUpdate(prompt, interactive, file)) {
            out.println("Kept existing " + relative(file) + " (primaryModel unchanged).");
            return;
        }
        JsonNode node = MAPPER.readTree(Files.readString(file));
        if (node == null || !node.isObject()) {
            err.println(relative(file) + " is not a JSON object; it was left unchanged.");
            return;
        }
        // Update in place, preserving unknown fields (provider add's discipline).
        ObjectNode spec = (ObjectNode) node;
        spec.put("primaryModel", ref.toString());
        if (!spec.has("identityId")) {
            spec.put("identityId", InitCommand.DEFAULT_IDENTITY_ID);
        }
        if (belt) {
            ArrayNode tools = spec.putArray("allowedTools");
            InitCommand.DEFAULT_ALLOWED_TOOLS.forEach(tools::add);
        }
        writeOwnerOnly(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n");
        out.println("Updated " + relative(file) + " (primaryModel " + ref + ").");
    }

    private void writeJson(ProviderAddCommand.Prompt prompt, PrintStream out, boolean interactive,
                           Path file, ObjectNode spec) throws IOException {
        String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
        if (Files.exists(file)) {
            if (decideUpdate(prompt, interactive, file)) {
                writeOwnerOnly(file, content);
                out.println("Updated " + relative(file) + ".");
            } else {
                out.println("Kept existing " + relative(file) + ".");
            }
            return;
        }
        writeOwnerOnly(file, content);
        out.println("Wrote " + relative(file) + ".");
    }

    private static ObjectNode telegramSpec(String token) {
        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("enabled", true);
        spec.put("botToken", token);
        return spec;
    }

    private static ObjectNode webSpec(String backend, String braveApiKey) {
        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("backend", backend);
        if (braveApiKey != null) {
            spec.put("braveApiKey", braveApiKey);
        }
        return spec;
    }

    /**
     * Keep-or-update for an existing file: interactively ask (default KEEP), non-interactively follow
     * {@code --update} (default keep) — a re-run never clobbers operator edits unprompted.
     */
    private boolean decideUpdate(ProviderAddCommand.Prompt prompt, boolean interactive, Path file) {
        if (!interactive) {
            return update;
        }
        return askYesNo(prompt, relative(file) + " already exists - update it? [y/N] ", false);
    }

    private void writeIfAbsent(Path file, String content, PrintStream out) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        writeOwnerOnly(file, content);
        out.println("Wrote " + relative(file) + ".");
    }

    /** Owner-only write ({@code 0700} dirs / {@code 0600} files on POSIX) — init's permission discipline. */
    private static void writeOwnerOnly(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (POSIX) {
            Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } else {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
        if (POSIX) {
            Files.setPosixFilePermissions(file, FILE_PERMS);
        }
    }

    private String relative(Path file) {
        return home.root().relativize(file).toString();
    }

    // --- prompt / provider helpers ---------------------------------------------------------------------

    /** Yes/no with a default: EOF or an empty line takes the default so a piped run never blocks. */
    static boolean askYesNo(ProviderAddCommand.Prompt prompt, String label, boolean defaultYes) {
        String answer = prompt.line(label);
        if (answer == null || answer.isBlank()) {
            return defaultYes;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private Set<String> installedProviderIds() {
        Set<String> ids = new TreeSet<>();
        for (ModelProvider p : providers) {
            ids.add(p.extensionId());
        }
        return ids;
    }

    /** Resolve the provider directly (no ledger) and run one chat — provider add's production smoke. */
    String smokeViaProvider(ModelRef ref) {
        for (ModelProvider p : providers) {
            if (p.extensionId().equals(ref.provider())) {
                return p.resolve(ref).chat(SMOKE_PROMPT);
            }
        }
        throw new IllegalStateException("No model provider for '" + ref.provider() + "' on the classpath.");
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

    /** The {@code --non-interactive} prompt: never reads anything, so the flag mode needs no stdin. */
    static final class NoPrompt implements ProviderAddCommand.Prompt {
        @Override
        public String line(String label) {
            return null;
        }

        @Override
        public String secret(String label) {
            return null;
        }
    }
}
