package ai.forvum.tools.multimodal;

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
 * Reads the multimodal tools' configuration from {@code $FORVUM_HOME/tools/multimodal.json} on demand per
 * invocation ("fixed code, configurable behavior", CLAUDE.md §1; #185). Unlike the subprocess tools, the
 * multimodal tools are FUNCTIONAL with no config — the vision model defaults to the agent's primary model,
 * a runtime property, not a config-shaped gap — so an absent file simply yields defaults (there is no
 * "not configured" state and no {@code configGaps} entry).
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same way
 * {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code FORVUM_HOME}),
 * falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson as a
 * {@code JsonNode} tree-walk into a plain {@link Spec} record (no reflective POJO binding → native-clean,
 * the {@code TtsConfig}/{@code ShellAllowlist} pattern).
 *
 * <p>Three knobs: {@code model} (an optional {@code provider:model} override for the vision sub-generation;
 * absent ⇒ the agent's primary model), {@code maxFileBytes} (the per-file size cap, stat-checked BEFORE the
 * bytes are read; default 5 MiB), and {@code maxPdfTextChars} (the extracted-text cap; default 50000 —
 * truncation appends a fixed ASCII marker, the [#176] bounded contract).
 */
@ApplicationScoped
public class MultimodalToolConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";
    /** Default per-file size cap (5 MiB), stat-checked before read; base64 inflates ~4/3. */
    static final long DEFAULT_MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** Default cap on extracted PDF text characters (the [#176] bounded fallback). */
    static final int DEFAULT_MAX_PDF_TEXT_CHARS = 50_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public MultimodalToolConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("multimodal.json");
    }

    /** Package-private constructor binding an explicit {@code tools/multimodal.json} path — for tests. */
    MultimodalToolConfig(Path configFile) {
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
     * The current multimodal configuration read from {@code tools/multimodal.json}. Returns
     * {@link Spec#defaults()} when the file is absent (the tools still work — the model defaults to the
     * agent's primary); throws {@link UncheckedIOException} on a malformed/unreadable file (a real
     * misconfiguration the operator must see, naming the PATH, never the content).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.defaults();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read multimodal config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/multimodal.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return Spec.defaults();
        }
        Optional<String> model = nonBlank(root, "model");

        JsonNode bytesNode = root.get("maxFileBytes");
        long maxFileBytes = bytesNode == null || !bytesNode.isNumber() || bytesNode.asLong() <= 0
                ? DEFAULT_MAX_FILE_BYTES
                : bytesNode.asLong();

        JsonNode charsNode = root.get("maxPdfTextChars");
        int maxPdfTextChars = charsNode == null || !charsNode.isNumber() || charsNode.asInt() <= 0
                ? DEFAULT_MAX_PDF_TEXT_CHARS
                : charsNode.asInt();

        return new Spec(model, maxFileBytes, maxPdfTextChars);
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText().strip());
    }

    /**
     * The multimodal tools' resolved configuration. Parsed by hand from the JSON tree (no reflective Jackson
     * binding into the record), so — like {@code TtsConfig.Spec} — it needs no {@code @RegisterForReflection}.
     *
     * @param model           an optional {@code provider:model} override for the vision sub-generation;
     *                        absent ⇒ the agent's primary model
     * @param maxFileBytes    the per-file size cap in bytes (stat-checked before read; default 5 MiB)
     * @param maxPdfTextChars the extracted-PDF-text character cap (default 50000; truncation adds a marker)
     */
    public record Spec(Optional<String> model, long maxFileBytes, int maxPdfTextChars) {

        /** The default spec: no model override (agent primary), 5 MiB file cap, 50000-char PDF text cap. */
        static Spec defaults() {
            return new Spec(Optional.empty(), DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_PDF_TEXT_CHARS);
        }

        /** The model override string, or {@code null} for the agent's primary model (the seam's contract). */
        public String modelOrNull() {
            return model.orElse(null);
        }
    }
}
