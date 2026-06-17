package ai.forvum.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the web tools' file-based configuration from {@code $FORVUM_HOME/tools/web.json} ("fixed code,
 * configurable behavior", CLAUDE.md §1): the operator sets the Brave Search API key and the
 * {@code allowPrivateNetwork} egress opt-in by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson
 * (brought transitively by {@code quarkus-rest-client-jackson}), mirroring {@code QdrantConfig.read()}.
 * With no {@code ~/.forvum/} the file is absent and {@link #read()} returns {@link Spec#empty()} (no key,
 * strict egress), so the module is INERT and the app boots in the CI native no-config smoke. The config is
 * read on demand so an operator's edit takes effect without a restart.
 *
 * <p>The Brave key MAY also come from {@code META-INF/microprofile-config.properties} / env (the
 * {@code quarkus.rest-client} default); {@code tools/web.json} is the richer, hot-editable source. The key
 * is never logged.
 */
@ApplicationScoped
public class WebToolConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public WebToolConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("web.json");
    }

    /** Package-private constructor binding an explicit {@code tools/web.json} path — for tests. */
    WebToolConfig(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    /** Package-private no-arg constructor for a test subclass that overrides {@link #read()}. */
    WebToolConfig() {
        this.configFile = null;
    }

    /** Pure home resolution, mirroring {@code ForvumHome.resolve}. Always absolute and normalized. */
    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /**
     * The current spec read from {@code tools/web.json}. Returns {@link Spec#empty()} if the file is
     * absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real misconfiguration
     * the operator must see). The exception message names the file path, never the key contents.
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read web tools config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/web.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return Spec.empty();
        }
        JsonNode keyNode = root.get("braveApiKey");
        Optional<String> braveApiKey = keyNode == null || keyNode.asText().isBlank()
                ? Optional.empty()
                : Optional.of(keyNode.asText().strip());

        JsonNode egressNode = root.get("allowPrivateNetwork");
        boolean allowPrivateNetwork = egressNode != null && egressNode.asBoolean(false);

        return new Spec(braveApiKey, allowPrivateNetwork, parseAllowedPorts(root.get("allowedPorts")));
    }

    /**
     * The operator-widened egress port allowlist from {@code allowedPorts} (a JSON array of ints): absent
     * or empty means "use the {@link EgressGuard#DEFAULT_ALLOWED_PORTS default}" (the empty set is the
     * signal {@code EgressGuard} reads as "fall back to default"). Non-numeric entries are ignored.
     */
    static Set<Integer> parseAllowedPorts(JsonNode node) {
        Set<Integer> ports = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                if (element.isInt() || element.canConvertToInt()) {
                    ports.add(element.asInt());
                }
            }
        }
        return ports;
    }

    /**
     * The web tools' resolved configuration.
     *
     * @param braveApiKey         the Brave Search API key, absent when unset (web.search is then inert).
     * @param allowPrivateNetwork whether web.fetch may reach internal/private addresses (default false).
     * @param allowedPorts        the operator-widened destination-port allowlist; empty = the
     *                            {@link EgressGuard#DEFAULT_ALLOWED_PORTS default} {80, 443, scheme-default}.
     */
    public record Spec(Optional<String> braveApiKey, boolean allowPrivateNetwork, Set<Integer> allowedPorts) {

        public Spec {
            allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
        }

        static Spec empty() {
            return new Spec(Optional.empty(), false, Set.of());
        }
    }
}
