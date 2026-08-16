package ai.forvum.sdk;

/**
 * The outbound channel-send SPI (#188): a channel plugin implements it to deliver an
 * engine-originated message to its external surface (a Telegram chat, ...). It closes the gap the
 * {@link ChannelProvider} discovery marker deliberately left open (M16 Resolution B): channels are
 * self-driving <em>inbound</em> consumers of {@link ChannelTurnDriver}, and until now the engine had no
 * push path — a cron's {@code last}/{@code explicit-to} output went to a logged sink
 * (P2-CRON-DELIVERY), and no tool could message a human surface.
 *
 * <p>It mirrors the {@link ChannelTurnDriver}/{@link TaskExecutor} shape: a PLAIN (non-sealed)
 * Quarkus-free interface carrying only JDK types, promoted to {@code forvum-sdk} so both sides stay
 * inside the layering — a Layer-3 channel implements it depending only on {@code forvum-sdk} (+
 * {@code forvum-core}), and the engine / the messaging tool resolve every installed implementation via
 * CDI ({@code Instance<ChannelSender>}), matching each sender to a channel by {@link #extensionId()}
 * (the same id as {@code channels/<id>.json} and the plugin's {@code META-INF/forvum/plugin.json}).
 *
 * <p>Implementations run on the caller's virtual thread and are blocking-imperative (section 3.8, no
 * Mutiny). They must never log or propagate a secret (e.g. a bot-token-bearing URL, repo lesson [M17]).
 */
public interface ChannelSender {

    /** Stable id of the owning channel extension, matching its {@code channels/<id>.json} config id. */
    String extensionId();

    /**
     * Deliver {@code text} to {@code target} on this channel.
     *
     * <p>{@code target} is channel-specific (for Telegram, a chat id in decimal form); a {@code null} or
     * blank target asks the channel to use its configured default destination. Returns {@code true} when
     * the message was handed to the channel's transport; returns {@code false} when the channel is not
     * configured to send (disabled, missing credentials, no resolvable default destination) so the
     * caller can fall back (e.g. cron delivery falls back to the logged sink). A malformed
     * {@code target} is rejected with {@link IllegalArgumentException}; a transport failure propagates
     * as a {@link RuntimeException} whose message carries no secret.
     */
    boolean send(String target, String text);
}
