package ai.forvum.engine.cron;

import java.util.Locale;

/**
 * Where a cron's isolated-agent reply goes after the turn (P2-CRON-DELIVERY, ULTRAPLAN section 7.2
 * item 22):
 *
 * <ul>
 *   <li>{@link #NONE} — the reply is dropped (the turn is still ledgered; this is the default when a
 *       cron declares no {@code delivery}, preserving M19 behavior);</li>
 *   <li>{@link #LAST} — the reply is delivered once per fire to the cron's last-output sink;</li>
 *   <li>{@link #EXPLICIT_TO} — the reply is delivered once per fire to a named channel target.</li>
 * </ul>
 *
 * <p>The JSON wire form is the lower-kebab token ({@code none}, {@code last}, {@code explicit-to}).
 */
public enum DeliveryMode {

    NONE("none"),
    LAST("last"),
    EXPLICIT_TO("explicit-to");

    private final String wire;

    DeliveryMode(String wire) {
        this.wire = wire;
    }

    /** The lower-kebab token used in {@code crons/<id>.json}. */
    public String wire() {
        return wire;
    }

    /**
     * Parse a {@code delivery.mode} token. The match is case-insensitive on the kebab form; any other
     * value is rejected so an unknown mode disables the cron at parse (and surfaces in {@code doctor}).
     */
    public static DeliveryMode fromWire(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("delivery.mode must be one of none|last|explicit-to.");
        }
        String normalized = token.strip().toLowerCase(Locale.ROOT);
        for (DeliveryMode mode : values()) {
            if (mode.wire.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Unknown delivery.mode '" + token + "'. Use one of none|last|explicit-to.");
    }
}
