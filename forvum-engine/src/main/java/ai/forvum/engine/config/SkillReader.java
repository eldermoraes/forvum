package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Reads raw skill prompt templates from {@code $FORVUM_HOME/skills/<id>.md}. */
@Singleton
public class SkillReader {

    private final ConfigLoader loader;
    private final Path dir;

    @Inject
    public SkillReader(ConfigLoader loader, ForvumHome home) {
        this.loader = loader;
        this.dir = home.skills();
    }

    /** The ids (file-name stems) of the {@code .md} skill files, sorted. */
    public List<String> ids() {
        return loader.listIds(dir, ".md");
    }

    /** The raw Markdown for skill {@code id}; empty if absent. */
    public Optional<String> read(String id) {
        return loader.readText(dir.resolve(id + ".md"));
    }
}
