package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;

/**
 * The pure #172 sanitization helpers: {@code safeFailureMessage} builds only from safe components (a
 * class name + the curated connection hint), and {@code redactedDiagnostic} masks secrets in the internal
 * log detail. Plain unit test (package-private statics).
 */
class SafeErrorFormattingTest {

    @Test
    void connectionFailureNamesClassAndModelHintButNoRawText() {
        RuntimeException e = new RuntimeException("wrapper sk-LEAK1234567890abcd /home/x/.ssh",
                new ConnectException("refused: secret /etc/shadow"));
        String msg = TurnService.safeFailureMessage(e, "ollama:qwen3");
        assertTrue(msg.contains("ConnectException"), "keeps the root-cause class name");
        assertTrue(msg.contains("ollama:qwen3"), "keeps the safe model hint");
        assertFalse(msg.contains("LEAK1234567890abcd"), "no secret");
        assertFalse(msg.contains("/home/x/.ssh"), "no path");
        assertFalse(msg.contains("/etc/shadow"), "no nested-cause path");
        assertFalse(msg.contains("refused"), "no raw cause message");
    }

    @Test
    void nonConnectionFailureNamesOnlyTheClassNoHint() {
        RuntimeException e = new RuntimeException("boom /etc/passwd",
                new IllegalStateException("tool arg leaked"));
        String msg = TurnService.safeFailureMessage(e, "ollama:x");
        assertTrue(msg.contains("IllegalStateException"), "keeps the root-cause class");
        assertFalse(msg.contains("/etc/passwd"), "no path");
        assertFalse(msg.contains("tool arg leaked"), "no raw cause message");
        assertFalse(msg.contains("Is the model provider running"),
                "no connection hint for a non-connection cause");
    }

    @Test
    void aPathologicalCauseChainFallsBackToTheGenericMessage() {
        Throwable pathological = new RuntimeException("x") {
            @Override
            public synchronized Throwable getCause() {
                throw new RuntimeException("cause access blows up");
            }
        };
        assertEquals("The turn could not be completed",
                TurnService.safeFailureMessage(pathological, "ollama:x"));
    }

    @Test
    void redactedDiagnosticMasksSecretsButKeepsTheClass() {
        Throwable cause = new IllegalStateException("call failed with sk-DISTINCTIVEBODY1234567");
        String d = TurnService.redactedDiagnostic(cause);
        assertFalse(d.contains("DISTINCTIVEBODY1234567"), "the internal log redacts the secret body");
        assertTrue(d.contains("IllegalStateException"), "but keeps the class for the operator");
    }

    @Test
    void redactedDiagnosticIsNullSafe() {
        assertEquals("(no cause)", TurnService.redactedDiagnostic(null));
    }
}
