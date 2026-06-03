package ai.forvum.core.event;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;

/**
 * Terminal failure event for a turn. The {@link #from} factory captures a {@link Throwable}'s class
 * name and rendered stack trace; a {@code null} cause leaves both fields {@code null}.
 */
public record ErrorEvent(
    Instant timestamp,
    UUID turnId,
    String code,
    String message,
    String exceptionClass,
    String stackTraceText
) implements AgentEvent {

    public static ErrorEvent from(Instant timestamp, UUID turnId,
                                  String code, String message, Throwable cause) {
        return new ErrorEvent(
            timestamp, turnId, code, message,
            cause == null ? null : cause.getClass().getName(),
            cause == null ? null : stackTraceToString(cause)
        );
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
