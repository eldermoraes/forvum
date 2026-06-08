package ai.forvum.engine.cron;

/**
 * The seam {@link CronScheduler} hands a cron's reply to for {@code last}/{@code explicit-to} delivery
 * (P2-CRON-DELIVERY, ULTRAPLAN section 7.2 item 22).
 *
 * <p><strong>Why a sink, not a channel push:</strong> the channel SPI ({@code ChannelProvider}) is a
 * pure build-time discovery marker (M16 Resolution B) — channels are <em>self-driving</em> consumers of
 * the {@code ChannelTurnDriver}; the engine has no outbound "send to channel" API to invoke. So a cron's
 * output is delivered to this isolated-agent result sink (the default logs it), keyed by the resolved
 * target. A future outbound channel-send surface can back this sink without changing the cron contract.
 *
 * <p>Implementations run on the cron's virtual thread (section 3.8) and must be blocking-imperative and
 * non-throwing for the caller's purposes — {@link CronScheduler} invokes them fire-and-forget and never
 * lets a sink failure abort the fire.
 */
public interface CronDeliverySink {

    /** Deliver one cron output. Called at most once per fire (in-execution dedupe lives in the caller). */
    void deliver(CronDelivery delivery);
}
