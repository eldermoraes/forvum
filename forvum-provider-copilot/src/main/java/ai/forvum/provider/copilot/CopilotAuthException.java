package ai.forvum.provider.copilot;

/**
 * An unchecked failure in the GitHub Copilot device-code login or token-exchange flow (#42). The message is
 * operator-facing (e.g. "run `forvum copilot login` again") and NEVER carries a token or other secret.
 */
public class CopilotAuthException extends RuntimeException {

    public CopilotAuthException(String message) {
        super(message);
    }

    public CopilotAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
