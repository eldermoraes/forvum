package ai.forvum.sdk;

/**
 * One media-generation request a tool hands to a {@link GenerationProvider} (#187): the {@link MediaKind}
 * to produce and the prompt describing it. A pure value (JDK types only), validated in the canonical
 * constructor — reflection-free and native-safe; the backend-specific request shape (model, size, format,
 * ...) is the provider's concern, configured by the operator, never the model's.
 *
 * @param kind   the kind of media to generate (never {@code null})
 * @param prompt the natural-language description of the media to generate (never blank)
 */
public record GenerationRequest(MediaKind kind, String prompt) {

    public GenerationRequest {
        if (kind == null) {
            throw new IllegalArgumentException(
                    "GenerationRequest kind must be non-null — one of the MediaKind constants.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(
                    "GenerationRequest prompt must be non-null and non-blank — the backend generates "
                  + "from it.");
        }
    }
}
