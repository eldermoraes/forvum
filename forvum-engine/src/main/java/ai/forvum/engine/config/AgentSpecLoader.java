package ai.forvum.engine.config;

import ai.forvum.core.AgentSpec;
import ai.forvum.core.ModelRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class AgentSpecLoader {

    private final ObjectMapper objectMapper;
    private final Path agentsDir = Path.of("agents");

    public AgentSpecLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentSpec load(String id) {
        Path file = agentsDir.resolve(id + ".json");
        JsonNode node;
        try (InputStream in = Files.newInputStream(file)) {
            node = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                "AgentSpecLoader failed to read " + file.toAbsolutePath()
              + ". Ensure the file exists, is readable, and contains valid JSON.",
                e);
        }

        if (!node.hasNonNull("systemPrompt")) {
            throw new IllegalStateException(
                "AgentSpec " + id + " is missing required 'systemPrompt' field. "
              + "Expected a non-null string in " + file.toAbsolutePath() + ".");
        }
        if (!node.hasNonNull("primaryModel")) {
            throw new IllegalStateException(
                "AgentSpec " + id + " is missing required 'primaryModel' field. "
              + "Expected a string like \"ollama:qwen3:1.7b\" in "
              + file.toAbsolutePath() + ".");
        }

        String systemPrompt = node.get("systemPrompt").asText();
        String modelSpec = node.get("primaryModel").asText();
        ModelRef primaryModel = ModelRef.parse(modelSpec);

        return new AgentSpec(id, systemPrompt, primaryModel);
    }
}
