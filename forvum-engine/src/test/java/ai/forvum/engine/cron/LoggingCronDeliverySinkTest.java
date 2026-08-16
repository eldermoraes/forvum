package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * Unit contract for the fallback {@link LoggingCronDeliverySink} (#180): both {@link DeliveryMode}
 * branches must render a delivery without touching any channel — the sink's whole contract is "never
 * throw, always surface the reply through the log", so a delivery in either mode completing normally
 * IS the observable behavior. Direct instantiation (no Quarkus boot) keeps this measured by the
 * Surefire-only coverage gate (X3).
 */
class LoggingCronDeliverySinkTest {

    private final LoggingCronDeliverySink sink = new LoggingCronDeliverySink();

    @Test
    void deliversAnExplicitToTargetWithoutThrowing() {
        CronDelivery delivery = new CronDelivery("daily-report", "main", "the report body",
                new Delivery(DeliveryMode.EXPLICIT_TO, "telegram"));
        assertDoesNotThrow(() -> sink.deliver(delivery),
                "the fallback sink must never fail an explicit-to delivery");
    }

    @Test
    void deliversALastOutputTargetWithoutThrowing() {
        CronDelivery delivery = new CronDelivery("daily-report", "main", "the report body",
                new Delivery(DeliveryMode.LAST, null));
        assertDoesNotThrow(() -> sink.deliver(delivery),
                "the fallback sink must never fail a last-output delivery");
    }
}
