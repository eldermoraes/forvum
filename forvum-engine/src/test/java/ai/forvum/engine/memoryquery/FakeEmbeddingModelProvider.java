package ai.forvum.engine.memoryquery;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code fake-embed}) that
 * supplies a DETERMINISTIC {@link EmbeddingModel} for the {@code MemoryQueryService} ITs — no live Ollama.
 * Each text maps to a fixed 8-dim vector by a stable per-character hash, so two identical texts embed
 * identically and the cosine ranking is reproducible. {@code resolve} (chat) is unsupported here — this
 * provider exists only to drive embedding.
 */
@ApplicationScoped
public class FakeEmbeddingModelProvider extends AbstractModelProvider {

    static final int DIM = 8;

    @Override
    public String extensionId() {
        return "fake-embed";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        throw new UnsupportedOperationException("fake-embed supplies no chat model");
    }

    @Override
    public EmbeddingModel resolveEmbedding(ModelRef ref) {
        // embedAll is the single abstract method of EmbeddingModel; embed(String) defaults onto it.
        return segments -> Response.from(segments.stream().map(TextSegment::text)
                .map(FakeEmbeddingModelProvider::embed).toList());
    }

    /** Deterministic 8-dim embedding: bucket characters by index mod DIM and accumulate their code points. */
    static Embedding embed(String text) {
        float[] vector = new float[DIM];
        for (int i = 0; i < text.length(); i++) {
            vector[i % DIM] += text.charAt(i);
        }
        return Embedding.from(vector);
    }
}
