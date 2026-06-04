package ai.forvum.engine.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared base for the {@code $FORVUM_HOME} subfolders that hold one {@code <id>.json} file per entry
 * (identities, crons, channels, mcp-servers). Returns raw {@link JsonNode} — no typed binding.
 */
abstract class JsonDirectoryReader {

    private final ConfigLoader loader;
    private final Path dir;

    protected JsonDirectoryReader(ConfigLoader loader, Path dir) {
        this.loader = loader;
        this.dir = dir;
    }

    /** The ids (file-name stems) of the {@code .json} files in this subfolder, sorted. */
    public List<String> ids() {
        return loader.listIds(dir, ".json");
    }

    /** The raw JSON for {@code id}; empty if absent. */
    public Optional<JsonNode> read(String id) {
        return loader.readJson(dir.resolve(id + ".json"));
    }
}
