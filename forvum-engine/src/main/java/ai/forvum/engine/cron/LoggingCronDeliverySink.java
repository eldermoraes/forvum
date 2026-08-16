package ai.forvum.engine.cron;

import io.quarkus.arc.DefaultBean;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * The fallback {@link CronDeliverySink}: it logs the delivered reply and its resolved target (P2-CRON-
 * DELIVERY). Since #188 the primary sink is {@link ChannelSendCronDeliverySink}, which pushes the reply
 * into a live channel through the SDK {@code ChannelSender} SPI and delegates here when no sender is
 * installed/configured for the resolved channel — so a cron's {@code last}/{@code explicit-to} output is
 * always at least surfaced through the ledger + this log line. {@code @DefaultBean} keeps this bean
 * injectable by its concrete type (the fallback seam) while the channel-backed sink wins the
 * {@code CronDeliverySink} injection point.
 */
@ApplicationScoped
@DefaultBean
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
