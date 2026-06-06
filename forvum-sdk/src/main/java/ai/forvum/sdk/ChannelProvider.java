package ai.forvum.sdk;

/**
 * SPI a channel plugin implements to bridge an external surface (TUI, Web, Telegram, ...) to the
 * agent runtime (ULTRAPLAN section 2.2, Layer 1). Sealed: third parties extend
 * {@link AbstractChannelProvider} rather than implementing this interface directly, which keeps the
 * set of channel implementations a closed, build-time-known set for native-image discovery.
 *
 * <p>This SPI is a pure discovery marker ({@code extensionId()} only). It carries NO transport method:
 * channels are self-driving — a channel's own inbound surface (a {@code @WebSocket} callback, a stdin
 * loop, a long-poll worker) hands the engine a {@code ai.forvum.core.ChannelMessage} and consumes the
 * turn through the SDK {@link ChannelTurnDriver} contract (implemented by the engine's {@code
 * TurnService}). The earlier plan to add inbound/outbound methods here was superseded at M16
 * (Resolution B): turn-driving lives on {@link ChannelTurnDriver}, not on this interface.
 */
public sealed interface ChannelProvider permits AbstractChannelProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();
}
