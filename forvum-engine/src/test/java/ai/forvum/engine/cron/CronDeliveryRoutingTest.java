package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The inline delivery-routing of {@link CronScheduler#deliver} (P2-CRON-DELIVERY): each {@link DeliveryMode}
 * routes a successful cron reply correctly through a stub {@link CronDeliverySink}, and the in-execution
 * dedupe holds — a single fire delivers at most once. Pure logic, no Quarkus boot (mirrors
 * {@link CronSchedulerTest}): the scheduler is constructed directly and only the {@code deliverySink}
 * collaborator is wired, since {@code deliver} touches nothing else.
 */
class CronDeliveryRoutingTest {

    /** A recording sink that captures every delivered payload (and can be made to throw). */
    static final class RecordingSink implements CronDeliverySink {
        final List<CronDelivery> delivered = new ArrayList<>();
        boolean explode;

        @Override
        public void deliver(CronDelivery delivery) {
            delivered.add(delivery);
            if (explode) {
                throw new RuntimeException("sink boom");
            }
        }
    }

    private static CronSpec spec(Delivery delivery) {
        return new CronSpec("daily", "0 * * * * ?", new AgentId("main"),
                ModelRef.parse("ollama:m"), "go", delivery);
    }

    private static CronScheduler schedulerWith(RecordingSink sink) {
        CronScheduler scheduler = new CronScheduler();
        scheduler.deliverySink = sink;
        return scheduler;
    }

    @Test
    void noneDeliversNothing() {
        RecordingSink sink = new RecordingSink();
        schedulerWith(sink).deliver(spec(Delivery.NONE), "the reply");
        assertTrue(sink.delivered.isEmpty(), "mode NONE must not reach the sink");
    }

    @Test
    void lastDeliversOnceToTheSink() {
        RecordingSink sink = new RecordingSink();
        schedulerWith(sink).deliver(spec(new Delivery(DeliveryMode.LAST, null)), "the reply");

        assertEquals(1, sink.delivered.size(), "mode LAST delivers exactly once per fire");
        CronDelivery d = sink.delivered.getFirst();
        assertEquals("daily", d.cronId());
        assertEquals("main", d.agentId());
        assertEquals("the reply", d.reply());
        assertEquals(DeliveryMode.LAST, d.delivery().mode());
    }

    @Test
    void explicitToDeliversOnceCarryingItsTarget() {
        RecordingSink sink = new RecordingSink();
        schedulerWith(sink).deliver(spec(new Delivery(DeliveryMode.EXPLICIT_TO, "telegram")), "ping");

        assertEquals(1, sink.delivered.size(), "mode EXPLICIT_TO delivers exactly once per fire");
        CronDelivery d = sink.delivered.getFirst();
        assertEquals(DeliveryMode.EXPLICIT_TO, d.delivery().mode());
        assertEquals("telegram", d.delivery().target());
        assertEquals("ping", d.reply());
    }

    @Test
    void oneFireDeliversAtMostOnce() {
        // The in-execution dedupe: deliver() is the single call site per fire, so one invocation = one delivery.
        RecordingSink sink = new RecordingSink();
        schedulerWith(sink).deliver(spec(new Delivery(DeliveryMode.LAST, null)), "r");
        assertEquals(1, sink.delivered.size(), "a single fire delivers its output at most once");
    }

    @Test
    void aSinkFailureNeverAbortsTheFire() {
        // Delivery is fire-and-forget: a throwing sink is isolated so the turn (already committed) is unaffected.
        RecordingSink sink = new RecordingSink();
        sink.explode = true;
        assertDoesNotThrow(() -> schedulerWith(sink).deliver(spec(new Delivery(DeliveryMode.LAST, null)), "r"),
                "a sink failure must be swallowed, not propagated");
        assertEquals(1, sink.delivered.size(), "the sink was still invoked once before it threw");
    }
}
