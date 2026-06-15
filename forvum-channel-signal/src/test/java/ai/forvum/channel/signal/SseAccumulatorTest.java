package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.forvum.channel.signal.SignalEvents.SseEvent;

import org.junit.jupiter.api.Test;

/**
 * The pure SSE framing state machine ({@link SseAccumulator}): lines accumulate {@code event:}/
 * {@code data:} fields until the empty dispatch line completes an event; comments (the daemon's
 * keep-alives) and unknown fields are ignored. A plain unit test — no socket, no clock.
 */
class SseAccumulatorTest {

    @Test
    void aSingleDataEventCompletesOnTheBlankLine() {
        SseAccumulator accumulator = new SseAccumulator();

        assertNull(accumulator.feed("event:receive"));
        assertNull(accumulator.feed("data:{\"envelope\":{}}"));
        SseEvent event = accumulator.feed("");

        assertEquals("receive", event.name());
        assertEquals("{\"envelope\":{}}", event.data());
    }

    @Test
    void oneLeadingSpaceAfterTheColonIsStripped() {
        SseAccumulator accumulator = new SseAccumulator();

        accumulator.feed("event: receive");
        accumulator.feed("data: {\"a\":1}");
        SseEvent event = accumulator.feed("");

        assertEquals("receive", event.name());
        assertEquals("{\"a\":1}", event.data(), "exactly ONE leading space is stripped per the SSE format");
    }

    @Test
    void multiLineDataJoinsWithNewlines() {
        SseAccumulator accumulator = new SseAccumulator();

        accumulator.feed("data:line one");
        accumulator.feed("data:line two");
        SseEvent event = accumulator.feed("");

        assertEquals("line one\nline two", event.data());
    }

    @Test
    void commentsAndKeepAlivesNeitherAccumulateNorComplete() {
        SseAccumulator accumulator = new SseAccumulator();

        assertNull(accumulator.feed(":keep-alive"));
        assertNull(accumulator.feed(""), "a blank line after only comments dispatches NO event");
    }

    @Test
    void unknownFieldsAreIgnored() {
        SseAccumulator accumulator = new SseAccumulator();

        accumulator.feed("id:42");
        accumulator.feed("retry:1000");
        accumulator.feed("data:x");
        SseEvent event = accumulator.feed("");

        assertNull(event.name(), "no event: field was fed");
        assertEquals("x", event.data());
    }

    @Test
    void stateResetsAfterADispatchSoEventsDoNotBleed() {
        SseAccumulator accumulator = new SseAccumulator();

        accumulator.feed("event:receive");
        accumulator.feed("data:first");
        accumulator.feed("");

        accumulator.feed("data:second");
        SseEvent second = accumulator.feed("");

        assertNull(second.name(), "the first event's name must not bleed into the second");
        assertEquals("second", second.data());
    }

    @Test
    void aFieldLineWithoutAColonIsTreatedAsAFieldWithAnEmptyValue() {
        SseAccumulator accumulator = new SseAccumulator();

        accumulator.feed("data");
        SseEvent event = accumulator.feed("");

        assertEquals("", event.data(), "a bare `data` line is a data field with the empty value");
    }
}
