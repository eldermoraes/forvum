package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Property-style invariants for {@link CostBudget} (mandatory per ULTRAPLAN section 10). */
class CostBudgetPropertyTest {

    private static final long SEED = 20260603L;
    private static final int CASES = 100;
    private static final List<ZoneId> ZONES =
        List.of(ZoneId.of("UTC"), ZoneId.of("America/Sao_Paulo"), ZoneId.of("Asia/Tokyo"));

    static Stream<Arguments> nonNegative() {       // (usd >= 0, tokens >= 0, window)
        Random r = new Random(SEED);
        return Stream.generate(() -> arguments(usd(r, false), tokens(r, false), window(r))).limit(CASES);
    }

    static Stream<Arguments> negativeUsdCases() {  // (usd < 0, window)
        Random r = new Random(SEED + 1);
        Stream<Arguments> edges = Stream.of(arguments(new BigDecimal("-0.0001"), window(r)));
        return Stream.concat(edges,
            Stream.generate(() -> arguments(usd(r, true), window(r))).limit(CASES));
    }

    static Stream<Arguments> negativeTokenCases() { // (tokens < 0, window)
        Random r = new Random(SEED + 2);
        Stream<Arguments> edges = Stream.of(arguments(-1L, window(r)), arguments(Long.MIN_VALUE, window(r)));
        return Stream.concat(edges,
            Stream.generate(() -> arguments(tokens(r, true), window(r))).limit(CASES));
    }

    @ParameterizedTest
    @MethodSource("nonNegative")
    void bothCapsPresentConstructsAndExposesFields(BigDecimal usd, long tokens, Window window) {
        CostBudget b = new CostBudget(usd, tokens, window);
        assertEquals(usd, b.maxUsd());
        assertEquals(tokens, b.maxTokens());
        assertEquals(window, b.window());
    }

    @ParameterizedTest
    @MethodSource("negativeUsdCases")
    void rejectsNegativeUsd(BigDecimal usd, Window window) {
        assertThrows(IllegalStateException.class, () -> new CostBudget(usd, null, window));
    }

    @ParameterizedTest
    @MethodSource("negativeTokenCases")
    void rejectsNegativeTokens(long tokens, Window window) {
        assertThrows(IllegalStateException.class, () -> new CostBudget(null, tokens, window));
    }

    private static BigDecimal usd(Random r, boolean negative) {   // scale-4; [0,~1000] or strictly < 0
        BigDecimal mag = BigDecimal.valueOf(r.nextDouble() * 1000.0).setScale(4, RoundingMode.HALF_UP);
        if (!negative) {
            return mag;
        }
        BigDecimal nonZero = mag.signum() == 0 ? new BigDecimal("0.0001") : mag;
        return nonZero.negate();
    }

    private static long tokens(Random r, boolean negative) {
        if (negative) {
            return Long.MIN_VALUE + (long) (r.nextDouble() * (Long.MAX_VALUE));  // some value < 0
        }
        return (long) (r.nextDouble() * 10_000_000L);
    }

    private static Window window(Random r) {
        if (r.nextBoolean()) {
            return new DayWindow(ZONES.get(r.nextInt(ZONES.size())));
        }
        return new SessionWindow(token(r), token(r));
    }

    private static String token(Random r) {
        int len = 1 + r.nextInt(8);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }
}
