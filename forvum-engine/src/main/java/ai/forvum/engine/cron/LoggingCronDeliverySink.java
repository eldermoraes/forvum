package ai.forvum.engine.cron;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * The default {@link CronDeliverySink}: it logs the delivered reply and its resolved target (P2-CRON-
 * DELIVERY). Because the channel SPI carries no outbound send surface (see {@link CronDeliverySink}),
 * v0.5 surfaces a cron's {@code last}/{@code explicit-to} output through the ledger + this log line
 * rather than pushing it into a live channel session. A later outbound channel-send capability replaces
 * this default without touching the cron contract.
 */
@ApplicationScoped
public class LoggingCronDeliverySink implements CronDeliverySink {

    private static final Logger LOG = Logger.getLogger(LoggingCronDeliverySink.class);

    @Override
    public void deliver(CronDelivery delivery) {
        String target = delivery.delivery().mode() == DeliveryMode.EXPLICIT_TO
                ? "channel '" + delivery.delivery().target() + "'"
                : "last-output";
        LOG.infof("Cron '%s' (agent '%s') delivered to %s: %s",
                delivery.cronId(), delivery.agentId(), target, delivery.reply());
    }
}
