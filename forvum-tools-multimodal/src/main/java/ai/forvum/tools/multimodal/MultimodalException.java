package ai.forvum.tools.multimodal;

/**
 * A multimodal-tool-internal refusal with an actionable, model-facing message — an unsupported/mismatched
 * media type, a file over the configured size cap, or an encrypted PDF. Thrown by the tools; the engine's
 * {@code ToolExecutor} records the invocation {@code error} (not {@code denied} — a Layer-3 tool cannot emit
 * the engine's pre-action verdict) and renders the message to the model, so the turn completes. The message
 * never echoes untrusted file content, only the source name and the actionable cause.
 */
public final class MultimodalException extends RuntimeException {

    public MultimodalException(String message) {
        super(message);
    }

    public MultimodalException(String message, Throwable cause) {
        super(message, cause);
    }
}
