package ai.forvum.provider.memory.qdrant;

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
 * Reads the Qdrant memory provider's file-based configuration from {@code $FORVUM_HOME/memory/qdrant.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables the provider, sets the Qdrant
 * URL/API key, and names the collection by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson
 * (brought transitively by {@code quarkus-rest-client-jackson}). With no {@code ~/.forvum/} the file is
 * absent and {@link #read()} returns {@link Spec#empty()} (disabled, no URL), so the provider is INERT and
 * the app boots in the CI native no-config smoke. The config is read on demand so an operator's edit takes
 * effect without a restart.
 */
@ApplicationScoped
public class QdrantConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";
    static final String DEFAULT_COLLECTION = "forvum_memory";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public QdrantConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("memory").resolve("qdrant.json");
    }

    /** Package-private constructor binding an explicit {@code memory/qdrant.json} path — for tests. */
    QdrantConfig(Path configFile) {
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
     * The current spec read from {@code memory/qdrant.json}. Returns {@link Spec#empty()} if the file is
     * absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real misconfiguration
     * the operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Qdrant memory config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code memory/qdrant.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return Spec.empty();
        }
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode == null || enabledNode.asBoolean(true);

        JsonNode urlNode = root.get("url");
        Optional<String> url = urlNode == null || urlNode.asText().isBlank()
                ? Optional.empty()
                : Optional.of(urlNode.asText().strip());

        JsonNode keyNode = root.get("apiKey");
        String apiKey = keyNode == null ? "" : keyNode.asText("");

        JsonNode collectionNode = root.get("collection");
        String collection = collectionNode == null || collectionNode.asText().isBlank()
                ? DEFAULT_COLLECTION
                : collectionNode.asText().strip();

        return new Spec(enabled, url, apiKey, collection);
    }

    /**
     * The Qdrant provider's resolved configuration.
     *
     * @param enabled    whether the provider is enabled (enabled unless {@code "enabled": false}); an
     *                   absent file is treated as disabled by {@link Spec#empty()}.
     * @param url        the Qdrant base URL, absent when unset (provider is then inert).
     * @param apiKey     the Qdrant API key, empty string when unset (a local unsecured Qdrant ignores it).
     * @param collection the collection name (defaults to {@code forvum_memory}).
     */
    public record Spec(boolean enabled, Optional<String> url, String apiKey, String collection) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), "", DEFAULT_COLLECTION);
        }

        /** Whether the provider is configured to serve: enabled AND a URL is present. */
        public boolean isActive() {
            return enabled && url.isPresent();
        }
    }
}
