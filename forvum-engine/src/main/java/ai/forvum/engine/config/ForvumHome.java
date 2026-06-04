package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves {@code $FORVUM_HOME} — the root of the user-editable configuration surface (the
 * {@code ~/.forvum/} tree).
 *
 * <p>The home is the {@code forvum.home} config property, which MicroProfile Config sources — highest
 * precedence first — from the {@code forvum.home} system property, then the {@code FORVUM_HOME}
 * environment variable (MP Config maps {@code forvum.home} ↔ {@code FORVUM_HOME}), falling back to
 * {@code <user.home>/.forvum}.
 *
 * <p>Resolution is pure path math performed once at construction and never touches the filesystem.
 * Whether the resolved tree actually exists is {@link ConfigWatcher}'s concern, not this type's.
 */
@Singleton
public class ForvumHome {

    static final String HOME_CONFIG_KEY = "forvum.home";
    static final String DEFAULT_DIR = ".forvum";

    private final Path root;

    @Inject
    public ForvumHome(@ConfigProperty(name = HOME_CONFIG_KEY) Optional<String> configuredHome) {
        this.root = resolve(configuredHome, System.getProperty("user.home"));
    }

    /** Package-private constructor binding an explicit root — used by tests with a {@code @TempDir}. */
    ForvumHome(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Pure resolution, package-private for unit testing: the configured home when present and
     * non-blank, otherwise {@code <userHome>/.forvum}. Always absolute and normalized.
     */
    static Path resolve(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> absolute(Path.of(value)))
                .orElseGet(() -> absolute(Path.of(userHome).resolve(DEFAULT_DIR)));
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The resolved {@code $FORVUM_HOME} root, absolute and normalized. */
    public Path root() {
        return root;
    }

    /** {@code $FORVUM_HOME/config.json} — global runtime config. */
    public Path configFile() {
        return root.resolve("config.json");
    }

    /** {@code $FORVUM_HOME/state} — operational state (the SQLite database lives here). */
    public Path state() {
        return root.resolve("state");
    }

    /** {@code $FORVUM_HOME/identities} — identity records. */
    public Path identities() {
        return root.resolve("identities");
    }

    /** {@code $FORVUM_HOME/agents} — agent personas ({@code .md}) and specs ({@code .json}). */
    public Path agents() {
        return root.resolve("agents");
    }

    /** {@code $FORVUM_HOME/skills} — named prompt templates ({@code .md}). */
    public Path skills() {
        return root.resolve("skills");
    }

    /** {@code $FORVUM_HOME/crons} — scheduled-job definitions ({@code .json}). */
    public Path crons() {
        return root.resolve("crons");
    }

    /** {@code $FORVUM_HOME/channels} — per-channel configuration ({@code .json}). */
    public Path channels() {
        return root.resolve("channels");
    }

    /** {@code $FORVUM_HOME/mcp-servers} — MCP server definitions ({@code .json}). */
    public Path mcpServers() {
        return root.resolve("mcp-servers");
    }
}
