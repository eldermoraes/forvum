package ai.forvum.tools.mediagen;

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
 * Reads the media-generation tool's configuration from {@code $FORVUM_HOME/tools/media-gen.json} on
 * demand per invocation ("fixed code, configurable behavior", CLAUDE.md §1; #187). The operator points
 * the bundled OpenAI-compatible images backend at their endpoint ({@code baseUrl}), an optional
 * {@code apiKey} (sent as a {@code Bearer} token; the {@code tools/web.json braveApiKey} posture — a
 * plain field in an operator-owned file), and an optional {@code model} — by editing one file, no
 * recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from
 * {@code FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with
 * Jackson as a {@code JsonNode} tree-walk into a plain {@link Spec} record (no reflective POJO binding
 * → native-clean, the ShellAllowlist/TtsConfig pattern).
 *
 * <p><strong>Inert when unconfigured.</strong> With no {@code ~/.forvum/} (the CI native no-config
 * smoke) the file is absent and {@link #read()} returns {@link Spec#unconfigured()}, whose
 * {@link Spec#isReady()} is {@code false} — the tool then returns an actionable "not configured" error
 * to the model rather than issuing a network call (never a crash, never a hang).
 */
@ApplicationScoped
public class MediaGenConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public MediaGenConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("media-gen.json");
    }

    /** Package-private constructor binding an explicit {@code tools/media-gen.json} path — for tests. */
    MediaGenConfig(Path configFile) {
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
     * The current media-generation configuration read from {@code tools/media-gen.json}. Returns
     * {@link Spec#unconfigured()} when the file is absent (the tool then reports "not configured");
     * throws {@link UncheckedIOException} on a malformed/unreadable file (a real misconfiguration the
     * operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.unconfigured();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read media-generation config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/media-gen.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return Spec.unconfigured();
        }
        return new Spec(nonBlank(root, "baseUrl"), nonBlank(root, "apiKey"), nonBlank(root, "model"));
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText().strip());
    }

    /**
     * The media-generation tool's resolved configuration. Parsed by hand from the JSON tree (no
     * reflective Jackson binding into the record), exactly like {@code TtsConfig.Spec} — so, like that
     * record, it needs no {@code @RegisterForReflection}: the native image never (de)serializes it
     * reflectively.
     *
     * @param baseUrl the OpenAI-compatible images endpoint base URL (e.g. {@code https://api.openai.com}
     *                or a local server), absent when unset — the readiness gate.
     * @param apiKey  an optional API key, sent on the {@code Authorization} header;
     *                absent means no auth header value (a local unsecured backend ignores it).
     * @param model   an optional model name sent in the request body; absent lets the backend default.
     */
    public record Spec(Optional<String> baseUrl, Optional<String> apiKey, Optional<String> model) {

        /** The unconfigured spec: no base URL, so {@link #isReady()} is false and the tool refuses. */
        static Spec unconfigured() {
            return new Spec(Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** Whether the bundled images backend can be called: a base URL is configured. */
        public boolean isReady() {
            return baseUrl.isPresent();
        }
    }
}
