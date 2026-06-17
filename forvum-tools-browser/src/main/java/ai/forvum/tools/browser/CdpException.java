package ai.forvum.tools.browser;

/**
 * An unchecked failure of the browser tool's CDP interaction (an unreachable Chrome, a CDP error response,
 * a missing page target, a command timeout). {@link BrowserToolProvider#invoke} catches it and returns a
 * clear, model-facing error string (the engine's {@code ToolExecutor} then audits the call {@code error}),
 * so a browser failure never crashes a turn — and an absent browser never blocks boot (the [M14]
 * graceful-absence contract: the WS is opened lazily on the first invoke, not at {@code @Startup}).
 */
public class CdpException extends RuntimeException {

    public CdpException(String message) {
        super(message);
    }

    public CdpException(String message, Throwable cause) {
        super(message, cause);
    }
}
