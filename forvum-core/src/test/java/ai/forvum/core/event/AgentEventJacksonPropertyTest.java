package ai.forvum.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.forvum.core.InvocationStatus;
import ai.forvum.core.ModelRef;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Jackson round-trip for every {@link AgentEvent} permit (mandatory per ULTRAPLAN section 10). */
class AgentEventJacksonPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final long SEED = 20260603L;
    private static final int CASES = 50;

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertEquals(value, MAPPER.readValue(MAPPER.writeValueAsString(value), type));
    }

    static Stream<TokenDelta> tokenDeltas() {
        Random r = new Random(SEED);
        return Stream.generate(() -> new TokenDelta(instant(r), text(r), modelRef(r))).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("tokenDeltas")
    void tokenDeltaRoundTrips(TokenDelta ev) throws Exception {
        assertRoundTrip(ev, TokenDelta.class);
    }

    static Stream<ToolInvoked> toolInvokeds() {
        Random r = new Random(SEED + 1);
        return Stream.generate(() -> new ToolInvoked(instant(r), r.nextLong(), text(r), text(r))).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("toolInvokeds")
    void toolInvokedRoundTrips(ToolInvoked ev) throws Exception {
        assertRoundTrip(ev, ToolInvoked.class);
    }

    static Stream<ToolResult> toolResults() {
        Random r = new Random(SEED + 2);
        InvocationStatus[] statuses = InvocationStatus.values();
        return Stream.generate(() -> new ToolResult(
            instant(r), r.nextLong(), text(r), statuses[r.nextInt(statuses.length)], r.nextLong())).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("toolResults")
    void toolResultRoundTrips(ToolResult ev) throws Exception {
        assertRoundTrip(ev, ToolResult.class);
    }

    static Stream<FallbackTriggered> fallbacks() {
        Random r = new Random(SEED + 3);
        return Stream.generate(() -> new FallbackTriggered(instant(r), modelRef(r), modelRef(r), text(r))).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("fallbacks")
    void fallbackTriggeredRoundTrips(FallbackTriggered ev) throws Exception {
        assertRoundTrip(ev, FallbackTriggered.class);
    }

    static Stream<Done> dones() {
        Random r = new Random(SEED + 4);
        return Stream.generate(() -> new Done(instant(r), UUID.randomUUID(), text(r))).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("dones")
    void doneRoundTrips(Done ev) throws Exception {
        assertRoundTrip(ev, Done.class);
    }

    static Stream<ErrorEvent> errors() {
        Random r = new Random(SEED + 5);
        return Stream.generate(() -> new ErrorEvent(
            instant(r), UUID.randomUUID(), text(r), text(r), nullableText(r), nullableText(r))).limit(CASES);
    }

    @ParameterizedTest
    @MethodSource("errors")
    void errorEventRoundTrips(ErrorEvent ev) throws Exception {
        assertRoundTrip(ev, ErrorEvent.class);
    }

    // --- seeded generators (mirror the former jqwik @Provide methods) ---

    private static Instant instant(Random r) {           // epoch second in [0, 4_102_444_800] + nanos
        long seconds = (long) (r.nextDouble() * 4_102_444_800L);
        int nanos = r.nextInt(1_000_000_000);
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static ModelRef modelRef(Random r) {
        return new ModelRef(lower(r, 1, 8), alnum(r, 1, 8));
    }

    private static String text(Random r) {               // printable ASCII + \n \t \r, length 0..40
        int len = r.nextInt(41);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int pick = r.nextInt(98);                     // 0..94 -> ' '..'~'; 95/96/97 -> \n \t \r
            if (pick < 95) {
                sb.append((char) (' ' + pick));
            } else {
                sb.append(pick == 95 ? '\n' : pick == 96 ? '\t' : '\r');
            }
        }
        return sb.toString();
    }

    private static String nullableText(Random r) {       // ~30% null, like jqwik injectNull(0.3)
        return r.nextDouble() < 0.3 ? null : text(r);
    }

    private static String lower(Random r, int min, int max) {
        int len = min + r.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }

    private static String alnum(Random r, int min, int max) {
        int len = min + r.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int n = r.nextInt(36);
            sb.append(n < 26 ? (char) ('a' + n) : (char) ('0' + n - 26));
        }
        return sb.toString();
    }
}
