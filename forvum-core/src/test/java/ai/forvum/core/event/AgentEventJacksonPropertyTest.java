package ai.forvum.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.ModelRef;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Jackson round-trip for every {@link AgentEvent} permit (mandatory per ULTRAPLAN section 10). */
class AgentEventJacksonPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertEquals(value, MAPPER.readValue(MAPPER.writeValueAsString(value), type));
    }

    @Provide
    Arbitrary<Instant> instants() {
        // Include a sub-second component so the round-trip exercises nanosecond precision, not just
        // whole seconds (every AgentEvent carries an Instant timestamp).
        Arbitrary<Long> seconds = Arbitraries.longs().between(0L, 4_102_444_800L);
        Arbitrary<Integer> nanos = Arbitraries.integers().between(0, 999_999_999);
        return Combinators.combine(seconds, nanos).as((s, n) -> Instant.ofEpochSecond(s, n));
    }

    @Provide
    Arbitrary<ModelRef> modelRefs() {
        Arbitrary<String> provider = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8);
        Arbitrary<String> model = Arbitraries.strings()
            .withCharRange('a', 'z').withCharRange('0', '9').ofMinLength(1).ofMaxLength(8);
        return Combinators.combine(provider, model).as(ModelRef::new);
    }

    /**
     * Printable ASCII plus the control characters ({@code \n}, {@code \t}, {@code \r}) that
     * {@code ErrorEvent.stackTraceText} always carries — so the round-trip exercises JSON escaping of
     * realistic multi-line values — while staying clear of unpaired-surrogate edge cases.
     */
    @Provide
    Arbitrary<String> texts() {
        return Arbitraries.strings().withCharRange(' ', '~').withChars('\n', '\t', '\r').ofMaxLength(40);
    }

    @Provide
    Arbitrary<String> nullableTexts() {
        return texts().injectNull(0.3);
    }

    @Property
    void tokenDeltaRoundTrips(@ForAll("instants") Instant ts, @ForAll("texts") String text,
                              @ForAll("modelRefs") ModelRef model) throws Exception {
        assertRoundTrip(new TokenDelta(ts, text, model), TokenDelta.class);
    }

    @Property
    void toolInvokedRoundTrips(@ForAll("instants") Instant ts, @ForAll long invocationId,
                              @ForAll("texts") String toolName, @ForAll("texts") String arguments) throws Exception {
        assertRoundTrip(new ToolInvoked(ts, invocationId, toolName, arguments), ToolInvoked.class);
    }

    @Property
    void toolResultRoundTrips(@ForAll("instants") Instant ts, @ForAll long invocationId,
                              @ForAll("texts") String result, @ForAll InvocationStatus status,
                              @ForAll long latencyMs) throws Exception {
        assertRoundTrip(new ToolResult(ts, invocationId, result, status, latencyMs), ToolResult.class);
    }

    @Property
    void fallbackTriggeredRoundTrips(@ForAll("instants") Instant ts, @ForAll("modelRefs") ModelRef failed,
                                     @ForAll("modelRefs") ModelRef next, @ForAll("texts") String reason)
            throws Exception {
        assertRoundTrip(new FallbackTriggered(ts, failed, next, reason), FallbackTriggered.class);
    }

    @Property
    void doneRoundTrips(@ForAll("instants") Instant ts, @ForAll("texts") String finalMessage) throws Exception {
        assertRoundTrip(new Done(ts, UUID.randomUUID(), finalMessage), Done.class);
    }

    @Property
    void errorEventRoundTrips(@ForAll("instants") Instant ts, @ForAll("texts") String code,
                              @ForAll("texts") String message, @ForAll("nullableTexts") String exceptionClass,
                              @ForAll("nullableTexts") String stackTraceText) throws Exception {
        assertRoundTrip(new ErrorEvent(ts, UUID.randomUUID(), code, message, exceptionClass, stackTraceText),
            ErrorEvent.class);
    }
}
