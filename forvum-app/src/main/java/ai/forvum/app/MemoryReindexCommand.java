package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.memoryquery.MemoryQueryService;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum memory reindex} (P3-2, #50): populate the {@code embedding} BLOB for every stored
 * {@code semantic_memory} fact of the given identity/agent that lacks one, using the configured embedding
 * model, so {@code forvum memory search} can rank it. Write-time embedding is deliberately NOT automatic
 * (it would put a blocking model call on the turn's critical path), so this explicit pass is how stored
 * facts become searchable. Exit 0 on success; 1 on a bad model ref or an embedding failure.
 */
@CommandLine.Command(
        name = "reindex",
        description = "Embed stored semantic-memory facts that lack an embedding so they become searchable.")
public class MemoryReindexCommand implements Callable<Integer> {

    @Inject
    MemoryQueryService service;

    @ConfigProperty(name = "forvum.memory.embedding-model", defaultValue = "ollama:nomic-embed-text")
    String configuredEmbeddingModel;

    @CommandLine.Option(
            names = "--identity",
            paramLabel = "<id>",
            description = "Owning identity to reindex (default ${DEFAULT-VALUE}).")
    String identityId = "default";

    @CommandLine.Option(
            names = "--agent",
            paramLabel = "<id>",
            description = "Owning agent to reindex (default ${DEFAULT-VALUE}).")
    String agentId = "main";

    @CommandLine.Option(
            names = "--model",
            paramLabel = "<provider:model>",
            description = "Embedding model ref (default: the forvum.memory.embedding-model config, "
                    + "or ollama:nomic-embed-text).")
    String modelOverride;

    @Override
    public Integer call() {
        ModelRef ref;
        try {
            ref = ModelRef.parse(modelOverride != null ? modelOverride : configuredEmbeddingModel);
        } catch (RuntimeException e) {
            System.err.println("Invalid embedding model ref: " + e.getMessage()
                    + " (expected provider:model, e.g. ollama:nomic-embed-text).");
            return 1;
        }

        int embedded;
        try {
            embedded = service.reindex(ref, identityId, agentId);
        } catch (RuntimeException e) {
            System.err.println("Reindex failed: " + e.getMessage()
                    + ". Is the embedding model (" + ref + ") available?");
            return 1;
        }
        System.out.println("Embedded " + embedded + " semantic-memory row(s) for identity '" + identityId
                + "', agent '" + agentId + "'.");
        return 0;
    }
}
