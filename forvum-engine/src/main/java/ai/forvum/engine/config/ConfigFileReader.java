package ai.forvum.engine.config;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Optional;

/** Reads the global {@code $FORVUM_HOME/config.json} as raw JSON. */
@Singleton
public class ConfigFileReader {

    private final ConfigLoader loader;
    private final Path file;

    @Inject
    public ConfigFileReader(ConfigLoader loader, ForvumHome home) {
        this.loader = loader;
        this.file = home.configFile();
    }

    /** The global config as raw JSON; empty if {@code config.json} is absent. */
    public Optional<JsonNode> read() {
        return loader.readJson(file);
    }
}
