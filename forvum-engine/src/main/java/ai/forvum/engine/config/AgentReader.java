package ai.forvum.engine.config;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads the raw agent surface from {@code $FORVUM_HOME/agents/}: a free-form Markdown persona
 * ({@code <id>.md}) and a structural spec ({@code <id>.json}). Returns raw {@link String}/{@link JsonNode};
 * typed parsing of the spec belongs to the future {@code AgentRegistry}, not M4.
 */
@Singleton
public class AgentReader {

    private final ConfigLoader loader;
    private final Path dir;

    @Inject
    public AgentReader(ConfigLoader loader, ForvumHome home) {
        this.loader = loader;
        this.dir = home.agents();
    }

    /** Agent ids — the {@code .json} spec is the source of truth for which agents exist; sorted. */
    public List<String> ids() {
        return loader.listIds(dir, ".json");
    }

    /** The agent's free-form Markdown persona ({@code <id>.md}); empty if absent. */
    public Optional<String> persona(String id) {
        return loader.readText(dir.resolve(id + ".md"));
    }

    /** The agent's raw JSON spec ({@code <id>.json}); empty if absent. */
    public Optional<JsonNode> spec(String id) {
        return loader.readJson(dir.resolve(id + ".json"));
    }
}
