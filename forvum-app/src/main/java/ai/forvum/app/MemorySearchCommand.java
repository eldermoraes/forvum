package ai.forvum.app;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.memoryquery.MemoryQueryService;
import ai.forvum.engine.memoryquery.SearchHit;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code forvum memory search '<text>'} (P3-2, #50): embed the query text with the configured embedding
 * model and return the nearest {@code semantic_memory} rows by cosine similarity (a pure-Java linear scan
 * over the stored vectors — Risk #2 deferred {@code vec0}). Rows that have not been embedded are invisible
 * to search — run {@code forvum memory reindex} first. Exit 0 on success; 1 on a bad model ref or an
 * embedding/search failure.
 */
@CommandLine.Command(
        name = "search",
        description = "Embed the text and return the nearest semantic-memory rows by cosine similarity.")
public class MemorySearchCommand implements Callable<Integer> {

    @Inject
    MemoryQueryService service;

    /** Default embedding model; overridable by config or {@code --model} so an operator picks their own. */
    @ConfigProperty(name = "forvum.memory.embedding-model", defaultValue = "ollama:nomic-embed-text")
    String configuredEmbeddingModel;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<text>",
            description = "The query text to embed and match against stored memory.")
    String text;

    @CommandLine.Option(
            names = "--top-k",
            paramLabel = "<n>",
            description = "Number of nearest neighbors to return (default ${DEFAULT-VALUE}).")
    int topK = 5;

    @CommandLine.Option(
            names = "--identity",
            paramLabel = "<id>",
            description = "Owning identity to search within (default ${DEFAULT-VALUE}).")
    String identityId = "default";

    @CommandLine.Option(
            names = "--agent",
            paramLabel = "<id>",
            description = "Owning agent to search within (default ${DEFAULT-VALUE}).")
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

        List<SearchHit> hits;
        try {
            hits = service.search(ref, text, identityId, agentId, topK);
        } catch (RuntimeException e) {
            System.err.println("Search failed: " + e.getMessage()
                    + ". Is the embedding model (" + ref + ") available, and has memory been reindexed?");
            return 1;
        }

        if (hits.isEmpty()) {
            System.out.println("No embedded memory found for identity '" + identityId + "', agent '"
                    + agentId + "'. Run `forvum memory reindex` to embed stored facts.");
            return 0;
        }
        for (SearchHit hit : hits) {
            System.out.printf("%.4f  %s = %s%n", hit.score(), hit.key(), hit.value());
        }
        return 0;
    }
}
