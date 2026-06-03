package ai.forvum.core.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.ZoneId;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property-based invariants for {@link CostBudget} (mandatory per ULTRAPLAN section 10). */
class CostBudgetPropertyTest {

    @Provide
    Arbitrary<Window> windows() {
        Arbitrary<Window> days = Arbitraries
            .of(ZoneId.of("UTC"), ZoneId.of("America/Sao_Paulo"), ZoneId.of("Asia/Tokyo"))
            .map(DayWindow::new);
        Arbitrary<String> token = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8);
        Arbitrary<Window> sessions = Combinators.combine(token, token).as(SessionWindow::new);
        return Arbitraries.oneOf(days, sessions);
    }

    @Provide
    Arbitrary<BigDecimal> nonNegativeUsd() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("1000")).ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> negativeUsd() {
        return Arbitraries.bigDecimals().between(new BigDecimal("-1000"), new BigDecimal("-0.0001")).ofScale(4);
    }

    @Provide
    Arbitrary<Long> nonNegativeTokens() {
        return Arbitraries.longs().between(0L, 10_000_000L);
    }

    @Provide
    Arbitrary<Long> negativeTokens() {
        return Arbitraries.longs().between(Long.MIN_VALUE, -1L);
    }

    @Property
    void bothCapsPresentConstructsAndExposesFields(@ForAll("nonNegativeUsd") BigDecimal usd,
                                                   @ForAll("nonNegativeTokens") Long tokens,
                                                   @ForAll("windows") Window window) {
        CostBudget b = new CostBudget(usd, tokens, window);
        assertEquals(usd, b.maxUsd());
        assertEquals(tokens, b.maxTokens());
        assertEquals(window, b.window());
    }

    @Property
    void rejectsNegativeUsd(@ForAll("negativeUsd") BigDecimal usd, @ForAll("windows") Window window) {
        assertThrows(IllegalStateException.class, () -> new CostBudget(usd, null, window));
    }

    @Property
    void rejectsNegativeTokens(@ForAll("negativeTokens") Long tokens, @ForAll("windows") Window window) {
        assertThrows(IllegalStateException.class, () -> new CostBudget(null, tokens, window));
    }
}
