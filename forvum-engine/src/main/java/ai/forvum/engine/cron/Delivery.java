package ai.forvum.engine.cron;

/**
 * A cron's output-delivery directive (P2-CRON-DELIVERY, ULTRAPLAN section 7.2 item 22): a
 * {@link DeliveryMode} plus, for {@link DeliveryMode#EXPLICIT_TO} only, the target channel id.
 *
 * <p>The canonical constructor enforces the mode↔target coupling so an <em>ambiguous</em> spec is
 * rejected at construction (and therefore at parse, so {@code CronScheduler} disables the cron and
 * {@code forvum doctor} surfaces it): {@code explicit-to} requires a non-blank target; {@code none}
 * and {@code last} forbid one (a target with a non-explicit mode is the ambiguity). The known-channel
 * cross-check lives in {@link CronSpecReader}, which holds the configured channel set.
 *
 * @param mode   the delivery mode
 * @param target the target channel id for {@link DeliveryMode#EXPLICIT_TO}; {@code null} otherwise
 */
public record Delivery(DeliveryMode mode, String target) {

    /** The default when a cron declares no {@code delivery} block — drop the reply (M19 behavior). */
    public static final Delivery NONE = new Delivery(DeliveryMode.NONE, null);

    public Delivery {
        if (mode == null) {
            throw new IllegalStateException("Delivery mode must be non-null.");
        }
        if (mode == DeliveryMode.EXPLICIT_TO) {
            if (target == null || target.isBlank()) {
                throw new IllegalStateException(
                        "delivery.mode 'explicit-to' requires a non-blank 'target' channel id.");
            }
            target = target.strip();
        } else if (target != null) {
            throw new IllegalStateException(
                    "delivery.mode '" + mode.wire() + "' must not carry a 'target' "
                  + "(a target is only valid with mode 'explicit-to').");
        }
    }
}
