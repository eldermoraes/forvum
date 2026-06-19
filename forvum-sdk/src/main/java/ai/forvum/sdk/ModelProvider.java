package ai.forvum.sdk;

import ai.forvum.core.ModelRef;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * SPI a model plugin implements to supply an LLM binding to the routing layer (ULTRAPLAN
 * section 2.2, Layer 1). Sealed: third parties extend {@link AbstractModelProvider}. M3 fixed the
 * structural contract and the id; the resolution method ({@link ModelRef} to a LangChain4j
 * {@link ChatModel}) is added by the Tier-B prelude that the provider milestones (M9-M12) implement.
 */
public sealed interface ModelProvider permits AbstractModelProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();

    /**
     * Resolve a {@link ModelRef} this provider handles to a LangChain4j {@link ChatModel}.
     *
     * <p>The engine's {@code LlmSelector} selects the provider whose {@link #extensionId()} matches the
     * ref's provider half, then calls this to obtain the model it invokes (which the engine wraps in its
     * fallback decorator). Implementations build/return the concrete model for {@code ref.model()}; they
     * do not touch the ledger or apply fallback — those are the engine's concern (ULTRAPLAN
     * section 4.3.5.1).
     */
    ChatModel resolve(ModelRef ref);

    /**
     * Resolve a {@link ModelRef} this provider handles to a LangChain4j {@link EmbeddingModel}, used by the
     * queryable semantic-memory search (P3-2, #50). Not every provider supplies embeddings, so this is a
     * defaulted prelude method (added in its first consumer's milestone, like {@link #resolve}): the
     * engine's memory-query path calls it only for the provider an operator names as the embedding model,
     * and the default throws {@link UnsupportedOperationException} so existing providers need no change.
     *
     * <p>Implementations build/return the concrete embedding model for {@code ref.model()} (e.g. an Ollama
     * {@code nomic-embed-text}); the engine owns the linear cosine search over the stored vectors. Like
     * {@link #resolve}, implementations do not touch the ledger.
     */
    default EmbeddingModel resolveEmbedding(ModelRef ref) {
        throw new UnsupportedOperationException(
                "Provider '" + extensionId() + "' does not supply an embedding model (ref: " + ref + ")");
    }
}
