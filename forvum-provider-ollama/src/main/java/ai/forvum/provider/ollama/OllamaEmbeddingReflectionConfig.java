package ai.forvum.provider.ollama;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for LangChain4j's Ollama <em>embedding</em> wire DTOs (P3-2,
 * #50, Risk #5).
 *
 * <p>{@code OllamaEmbeddingModel} serializes its request to JSON with Jackson and deserializes the
 * response the same way (both {@code EmbeddingRequest} and {@code EmbeddingResponse} carry
 * {@code @JsonInclude}/{@code @JsonNaming} annotations). In a GraalVM native image Jackson can only
 * bind a type whose constructors/fields/accessors were registered at build time; without that
 * registration the request serializes to an EMPTY object — the {@code model} field is dropped — so
 * Ollama receives {@code {"model": ""}} and replies {@code {"error":"model '' not found"}}, failing
 * {@code forvum memory reindex}/{@code search} on the native binary while the JVM works.
 *
 * <p>The {@code quarkus-langchain4j-ollama} extension registers only the CHAT DTOs
 * ({@code OllamaChatRequest}/{@code OllamaChatResponse}) for native reflection — because its own
 * synthetic beans wire only the chat (and config-driven embedding) path — so the embedding DTOs that
 * Forvum builds <em>programmatically</em> ({@code OllamaEmbeddingModel.builder()}) are never covered.
 * This holder closes that gap, mirroring the chat path's own registration.
 *
 * <p>The two classes are package-private in {@code dev.langchain4j.model.ollama}, so they are named via
 * {@link RegisterForReflection#classNames()} rather than {@code targets()}. {@code methods}/{@code fields}
 * default to {@code true}, which is what Jackson needs both to write the request and read the response;
 * this is JSON via Jackson (not Java {@code ObjectOutputStream}), so {@code serialization} stays
 * {@code false}. Quarkus build-time-scans this holder regardless of reachability.
 */
@RegisterForReflection(classNames = {
        "dev.langchain4j.model.ollama.EmbeddingRequest",
        "dev.langchain4j.model.ollama.EmbeddingResponse"
})
public final class OllamaEmbeddingReflectionConfig {

    private OllamaEmbeddingReflectionConfig() {
    }
}
