package ai.forvum.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the browser tool's file-based configuration from {@code $FORVUM_HOME/tools/browser.json} ("fixed
 * code, configurable behavior", CLAUDE.md §1): the operator enables the tool, points it at the Chrome
 * remote-debugging endpoint, and tunes timeouts by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson
 * (brought transitively by {@code quarkus-arc}/the platform). With no {@code ~/.forvum/} the file is absent
 * and {@link #read()} returns {@link Spec#defaults()} (disabled), so the tool is INERT and the app boots in
 * the CI native no-config smoke. The config is read on demand so an operator's edit takes effect without a
 * restart. Coexists with {@code tools/shell.json} (#27) under {@code tools/} — different stems.
 */
@ApplicationScoped
public class BrowserConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";
    static final String DEFAULT_DEBUG_URL = "http://localhost:9222";
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    static final int DEFAULT_COMMAND_TIMEOUT_MS = 15000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public BrowserConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("browser.json");
    }

    /** Package-private constructor binding an explicit {@code tools/browser.json} path — for tests. */
    BrowserConfig(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    /** Pure home resolution, mirroring {@code ForvumHome.resolve}. Always absolute and normalized. */
    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /**
     * The current spec read from {@code tools/browser.json}. Returns {@link Spec#defaults()} (disabled) if
     * the file is absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real
     * misconfiguration the operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.defaults();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read browser tool config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/browser.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return Spec.defaults();
        }
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode != null && enabledNode.asBoolean(false);

        JsonNode urlNode = root.get("debugUrl");
        String debugUrl = urlNode == null || urlNode.asText().isBlank()
                ? DEFAULT_DEBUG_URL
                : urlNode.asText().strip();

        JsonNode connectNode = root.get("connectTimeoutMs");
        int connectTimeoutMs = connectNode == null || !connectNode.isInt() || connectNode.asInt() <= 0
                ? DEFAULT_CONNECT_TIMEOUT_MS
                : connectNode.asInt();

        JsonNode navNode = root.get("navigateTimeoutMs");
        int commandTimeoutMs = navNode == null || !navNode.isInt() || navNode.asInt() <= 0
                ? DEFAULT_COMMAND_TIMEOUT_MS
                : navNode.asInt();

        return new Spec(enabled, debugUrl, connectTimeoutMs, commandTimeoutMs);
    }

    /**
     * The browser tool's resolved configuration.
     *
     * @param enabled          whether the tool is active (default {@code false}: an absent or
     *                         {@code "enabled": false} file leaves the tool inert).
     * @param debugUrl         the Chrome {@code --remote-debugging-port} HTTP base (default
     *                         {@code http://localhost:9222}); CDP discovery GETs hit {@code /json/version}
     *                         and {@code /json} here.
     * @param connectTimeoutMs the WebSocket / discovery connect timeout in milliseconds.
     * @param commandTimeoutMs the per-command (and navigate) await timeout in milliseconds.
     */
    public record Spec(boolean enabled, String debugUrl, int connectTimeoutMs, int commandTimeoutMs) {

        static Spec defaults() {
            return new Spec(false, DEFAULT_DEBUG_URL, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_COMMAND_TIMEOUT_MS);
        }
    }
}
