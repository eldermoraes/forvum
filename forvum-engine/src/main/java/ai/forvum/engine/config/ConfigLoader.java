package ai.forvum.engine.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Thin, Panache-less I/O surface over {@code $FORVUM_HOME}. It is the single place file reads and
 * Jackson parsing happen for M4. It returns raw {@link JsonNode}/{@link String} — no typed/domain
 * binding (that is owned by later milestones, e.g. the {@code AgentRegistry}). A missing file or
 * directory yields an empty result rather than an exception; genuine I/O failures surface as
 * {@link UncheckedIOException}.
 */
@Singleton
public class ConfigLoader {

    private final ObjectMapper mapper;

    @Inject
    public ConfigLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Reads and parses a JSON file; empty if the file is absent. */
    public Optional<JsonNode> readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JSON config file: " + file, e);
        }
    }

    /** Reads a UTF-8 text file; empty if the file is absent. */
    public Optional<String> readText(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read text config file: " + file, e);
        }
    }

    /**
     * Lists the ids (file-name stems, without {@code suffix}) of regular files in {@code dir} whose
     * name ends with {@code suffix}, sorted. Empty if the directory is absent.
     */
    public List<String> listIds(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .map(name -> name.substring(0, name.length() - suffix.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list config directory: " + dir, e);
        }
    }
}
