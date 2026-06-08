package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Canonical-constructor invariants of the {@link Delivery} record + {@link DeliveryMode} parsing
 * (P2-CRON-DELIVERY). The record enforces the mode↔target coupling that makes an ambiguous spec
 * un-constructable; the reader layers the known-channel cross-check on top.
 */
class DeliveryTest {

    @Test
    void noneConstantIsModeNoneWithNoTarget() {
        assertEquals(DeliveryMode.NONE, Delivery.NONE.mode());
        assertNull(Delivery.NONE.target());
    }

    @Test
    void explicitToKeepsItsTarget() {
        Delivery d = new Delivery(DeliveryMode.EXPLICIT_TO, "telegram");
        assertEquals("telegram", d.target());
    }

    @Test
    void explicitToStripsItsTarget() {
        assertEquals("telegram", new Delivery(DeliveryMode.EXPLICIT_TO, "  telegram  ").target());
    }

    @Test
    void rejectsANullMode() {
        assertThrows(IllegalStateException.class, () -> new Delivery(null, null));
    }

    @Test
    void rejectsExplicitToWithoutATarget() {
        assertThrows(IllegalStateException.class, () -> new Delivery(DeliveryMode.EXPLICIT_TO, null));
        assertThrows(IllegalStateException.class, () -> new Delivery(DeliveryMode.EXPLICIT_TO, "  "));
    }

    /** A target with a non-explicit mode is the ambiguity P2-CRON-DELIVERY rejects. */
    @ParameterizedTest
    @EnumSource(value = DeliveryMode.class, names = {"NONE", "LAST"})
    void rejectsATargetWithANonExplicitMode(DeliveryMode mode) {
        assertThrows(IllegalStateException.class, () -> new Delivery(mode, "telegram"));
    }

    @ParameterizedTest
    @EnumSource(DeliveryMode.class)
    void wireRoundTripsForEveryMode(DeliveryMode mode) {
        assertSame(mode, DeliveryMode.fromWire(mode.wire()));
    }

    @Test
    void fromWireRejectsBlankAndUnknown() {
        assertThrows(IllegalStateException.class, () -> DeliveryMode.fromWire(""));
        assertThrows(IllegalStateException.class, () -> DeliveryMode.fromWire(null));
        assertThrows(IllegalStateException.class, () -> DeliveryMode.fromWire("explicitto"));
    }
}
