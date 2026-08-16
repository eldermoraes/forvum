package ai.forvum.sdk;

/**
 * The #188 outbound-messaging seam (Resolution B, the {@link SessionAccess} pattern): the Layer-3
 * {@code forvum-tools-messaging} plugin holds only the {@code message.send} ToolSpec and delegates the
 * ENTIRE security envelope to the engine through this interface — the fail-closed destination allowlist
 * ({@code $FORVUM_HOME/tools/message-send.json}; an absent or empty allowlist refuses every send), the
 * configured-channel and installed-sender resolution, and the {@link OutputGuard} chain over the egress
 * text BEFORE it reaches a {@link ChannelSender}. Keeping the policy engine-side means a plugin can
 * never ship a variant that skips it (the Layer-3 module depends only on {@code forvum-sdk} +
 * {@code forvum-core}).
 *
 * <p>A plain (non-sealed) interface with the engine as sole production implementor — the
 * P2-TASKLEDGER sink-SPI shape. Implementations run blocking on the calling virtual thread.
 */
public interface MessageAccess {

    /**
     * Deliver {@code text} to {@code target} on the configured channel {@code channelId}, subject to the
     * engine's destination allowlist and output guards.
     *
     * @param channelId the configured channel id (a {@code channels/<id>.json})
     * @param target    the channel-specific destination; blank resolves to the SOLE allowlisted
     *                  recipient when exactly one is configured, and is refused otherwise (the engine
     *                  never falls through to a channel-side arbitrary default)
     * @param text      the message text; the engine runs it through the output-guard chain before any
     *                  sender sees it
     * @return a short outcome line for the model (sent, or an actionable not-sent reason)
     * @throws IllegalArgumentException when the channel is not allowlisted, the target is not an
     *                                  allowlisted recipient, or no installed sender matches
     * @throws RuntimeException         when an output guard blocks the egress (the engine surfaces its
     *                                  filtered disposition; the text never reaches the sender)
     */
    String send(String channelId, String target, String text);
}
