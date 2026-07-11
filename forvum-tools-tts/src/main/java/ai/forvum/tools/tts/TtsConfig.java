package ai.forvum.tools.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the TTS tool's configuration from {@code $FORVUM_HOME/tools/tts.json} on demand per invocation
 * ("fixed code, configurable behavior", CLAUDE.md §1; #186). The operator points the tool at their
 * OPERATOR-installed piper binary ({@code piperBin}), a default ONNX voice model ({@code piperVoice},
 * passed via {@code -m}), an optional {@code voices} map (voice NAME → ONNX path, what makes the tool's
 * {@code [voice]} parameter real), and a {@code timeoutSeconds} — by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson as
 * a {@code JsonNode} tree-walk into a plain {@link Spec} record (no reflective POJO binding → native-clean,
 * the ShellAllowlist pattern).
 *
 * <p><strong>Inert when unconfigured.</strong> With no {@code ~/.forvum/} (the CI native no-config smoke)
 * the file is absent and {@link #read()} returns {@link Spec#unconfigured()}, whose {@link Spec#isReady()}
 * is {@code false} — the tool then returns an actionable "not configured" error to the model rather than
 * launching a process (never a crash, never a hang).
 */
@ApplicationScoped
public class TtsConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";
    /** Default per-subprocess timeout when the operator does not set {@code timeoutSeconds} (piper on CPU). */
    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public TtsConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("tts.json");
    }

    /** Package-private constructor binding an explicit {@code tools/tts.json} path — for tests. */
    TtsConfig(Path configFile) {
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
     * The current TTS configuration read from {@code tools/tts.json}. Returns {@link Spec#unconfigured()}
     * when the file is absent (the tool then reports "not configured"); throws {@link UncheckedIOException}
     * on a malformed/unreadable file (a real misconfiguration the operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.unconfigured();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read TTS config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/tts.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return Spec.unconfigured();
        }

        Optional<String> piperBin = nonBlank(root, "piperBin");
        Optional<String> piperVoice = nonBlank(root, "piperVoice");

        Map<String, String> voices = new LinkedHashMap<>();
        JsonNode voicesNode = root.get("voices");
        if (voicesNode != null && voicesNode.isObject()) {
            voicesNode.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (name != null && !name.isBlank() && value != null && value.isTextual()
                        && !value.asText().isBlank()) {
                    voices.put(name.strip(), value.asText().strip());
                }
            });
        }

        JsonNode timeoutNode = root.get("timeoutSeconds");
        int timeoutSeconds = timeoutNode == null || !timeoutNode.isNumber() || timeoutNode.asInt() <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutNode.asInt();

        return new Spec(piperBin, piperVoice, Map.copyOf(voices), timeoutSeconds);
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText().strip());
    }

    /**
     * The TTS tool's resolved configuration. Parsed by hand from the JSON tree (no reflective Jackson
     * binding into the record), exactly like {@code ShellAllowlist.Spec} — so, like that record, it needs
     * no {@code @RegisterForReflection}: the native image never (de)serializes it reflectively.
     *
     * @param piperBin       the operator-installed piper binary path (TTS), absent when unset.
     * @param piperVoice     the default piper ONNX voice model passed via {@code -m}, absent when unset.
     * @param voices         an OPTIONAL map of voice NAME → ONNX voice model path; a {@code voice} argument
     *                       to {@code tts.speak} selects one by name (so the model can never point piper at
     *                       an arbitrary filesystem path). May be empty (only the default voice available).
     * @param timeoutSeconds the per-synthesis timeout in seconds (default {@code 120}).
     */
    public record Spec(Optional<String> piperBin, Optional<String> piperVoice,
                       Map<String, String> voices, int timeoutSeconds) {

        /** The unconfigured spec: no binary/voice, so {@link #isReady()} is false and the tool refuses. */
        static Spec unconfigured() {
            return new Spec(Optional.empty(), Optional.empty(), Map.of(), DEFAULT_TIMEOUT_SECONDS);
        }

        /** Whether the tool can synthesize: both the piper binary and the default voice are configured. */
        public boolean isReady() {
            return piperBin.isPresent() && piperVoice.isPresent();
        }

        /**
         * Resolve the ONNX voice model path for a requested voice name. A blank/absent name selects the
         * default {@code piperVoice}; a named voice must appear in the {@code voices} map.
         *
         * @param requestedVoice the voice name from the tool call, or {@code null}/blank for the default
         * @return the resolved ONNX voice model path
         * @throws TtsException if a named voice is not in the configured {@code voices} map
         */
        public String resolveVoice(String requestedVoice) {
            if (requestedVoice == null || requestedVoice.isBlank()) {
                return piperVoice().orElseThrow(() -> new TtsException(
                        "tts.speak is not configured: set piperVoice in $FORVUM_HOME/tools/tts.json."));
            }
            String voice = requestedVoice.strip();
            String path = voices.get(voice);
            if (path == null) {
                throw new TtsException("tts.speak voice '" + voice + "' is not configured. Available "
                        + "voices: " + voices.keySet() + " (declare them under 'voices' in "
                        + "tools/tts.json).");
            }
            return path;
        }
    }
}
