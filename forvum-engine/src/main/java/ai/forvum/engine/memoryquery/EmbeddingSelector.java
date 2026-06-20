package ai.forvum.engine.memoryquery;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.ModelProvider;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Resolves a {@link ModelRef} to an {@link EmbeddingModel} via the installed {@link ModelProvider}s, for
 * the queryable semantic-memory search (P3-2, #50). Mirrors {@code LlmSelector.providerFor}: it selects
 * the provider whose {@link ModelProvider#extensionId()} matches the ref's provider half and calls the
 * {@code resolveEmbedding} prelude. Unlike {@code LlmSelector} this is NOT ledger-wrapped — embedding is a
 * support operation for {@code memory search}/{@code reindex}, not a turn.
 */
@ApplicationScoped
public class EmbeddingSelector {

    @Inject
    Instance<ModelProvider> providers;

    /**
     * Resolve {@code ref} to its embedding model. Throws {@link IllegalStateException} when no installed
     * provider handles the ref's provider half, and surfaces the provider's own
     * {@link UnsupportedOperationException} when that provider supplies no embedding model.
     */
    public EmbeddingModel resolve(ModelRef ref) {
        for (ModelProvider provider : providers) {
            if (provider.extensionId().equals(ref.provider())) {
                return provider.resolveEmbedding(ref);
            }
        }
        throw new IllegalStateException(
                "No model provider for '" + ref.provider() + "' (from " + ref
                        + "). Is the matching provider plugin on the classpath?");
    }
}
