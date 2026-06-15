package ai.forvum.channel.signal;

import ai.forvum.channel.signal.SignalEvents.SseEvent;

/**
 * A minimal, pure Server-Sent-Events framing state machine: feed it the stream's lines one at a time
 * (already split by the JDK {@code BodyHandlers.ofLines()}, so no trailing line terminators) and it
 * assembles complete {@link SseEvent}s. Per the SSE wire format:
 *
 * <ul>
 *   <li>an EMPTY line dispatches the accumulated event (if any field was set) and resets;</li>
 *   <li>a line starting with {@code :} is a comment (the daemon's keep-alives) — ignored, and it does
 *       NOT complete an event;</li>
 *   <li>{@code event:} sets the event name; {@code data:} appends a data line (multi-line data joined
 *       with {@code \n}); one leading space after the colon is stripped; other fields ({@code id:},
 *       {@code retry:}) are ignored.</li>
 * </ul>
 *
 * <p>One instance per stream connection, used from the single consuming virtual thread — deliberately
 * NOT thread-safe (no shared state, CLAUDE.md §3.8 needs no lock here). Pure: no IO, no clock, so the
 * framing is unit-testable line by line.
 */
final class SseAccumulator {

    private String eventName;
    private StringBuilder data;

    /**
     * Feed one line; returns the completed {@link SseEvent} when {@code line} is the empty dispatch
     * line and at least one field had accumulated, otherwise {@code null}.
     */
    SseEvent feed(String line) {
        if (line.isEmpty()) {
            if (eventName == null && data == null) {
                return null; // nothing accumulated (e.g. the blank line after a comment) — no event
            }
            SseEvent event = new SseEvent(eventName, data == null ? null : data.toString());
            eventName = null;
            data = null;
            return event;
        }
        if (line.startsWith(":")) {
            return null; // a comment / keep-alive heartbeat
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1); // the SSE format strips ONE leading space after the colon
        }
        switch (field) {
            case "event" -> eventName = value;
            case "data" -> {
                if (data == null) {
                    data = new StringBuilder(value);
                } else {
                    data.append('\n').append(value); // multi-line data joins with \n
                }
            }
            default -> {
                // id:, retry:, unknown fields — ignored.
            }
        }
        return null;
    }
}
