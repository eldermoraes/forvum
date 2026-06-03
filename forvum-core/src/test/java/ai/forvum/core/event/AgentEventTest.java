package ai.forvum.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.ModelRef;

/** Structural checks for the {@link AgentEvent} sealed hierarchy (ULTRAPLAN section 4.3.2). */
class AgentEventTest {

    private static final Instant TS = Instant.parse("2026-06-03T12:00:00Z");
    private static final ModelRef MODEL = new ModelRef("ollama", "qwen3:1.7b");

    /**
     * Exhaustive switch with exactly the six permits and NO {@code default} branch. This compiles
     * only because {@link AgentEvent} is sealed and every permit is handled — the structural proof
     * required by section 4.3.2. Adding/removing a permit breaks compilation here.
     */
    private static String kind(AgentEvent e) {
        return switch (e) {
            case TokenDelta t -> "token";
            case ToolInvoked t -> "invoked";
            case ToolResult t -> "result";
            case FallbackTriggered f -> "fallback";
            case Done d -> "done";
            case ErrorEvent er -> "error";
        };
    }

    @Test
    void exhaustiveSwitchCoversEveryPermitWithoutDefault() {
        assertEquals("token", kind(new TokenDelta(TS, "hi", MODEL)));
        assertEquals("invoked", kind(new ToolInvoked(TS, 1L, "fs.read", "{}")));
        assertEquals("result", kind(new ToolResult(TS, 1L, "ok", InvocationStatus.OK, 5L)));
        assertEquals("fallback",
            kind(new FallbackTriggered(TS, MODEL, MODEL, FallbackReasons.TIMEOUT)));
        assertEquals("done", kind(new Done(TS, UUID.randomUUID(), "bye")));
        assertEquals("error",
            kind(ErrorEvent.from(TS, UUID.randomUUID(), "boom", "msg", new IllegalStateException("x"))));
    }

    @Test
    void everyEventExposesItsTimestamp() {
        assertEquals(TS, new TokenDelta(TS, "hi", MODEL).timestamp());
        assertEquals(TS, new Done(TS, UUID.randomUUID(), "bye").timestamp());
    }

    @Test
    void errorEventFromCapturesThrowable() {
        ErrorEvent e = ErrorEvent.from(TS, UUID.randomUUID(), "code", "message",
            new IllegalArgumentException("bad"));
        assertEquals("java.lang.IllegalArgumentException", e.exceptionClass());
        assertNotNull(e.stackTraceText());
        assertTrue(e.stackTraceText().contains("IllegalArgumentException"));
    }

    @Test
    void errorEventFromToleratesNullCause() {
        ErrorEvent e = ErrorEvent.from(TS, UUID.randomUUID(), "code", "message", null);
        assertNull(e.exceptionClass());
        assertNull(e.stackTraceText());
    }

    @Test
    void fallbackReasonsAreStableTokens() {
        assertEquals("rate_limit", FallbackReasons.RATE_LIMIT);
        assertEquals("timeout", FallbackReasons.TIMEOUT);
        assertEquals("server_error", FallbackReasons.SERVER_ERROR);
        assertEquals("cost_budget", FallbackReasons.COST_BUDGET);
    }
}
