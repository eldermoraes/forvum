package ai.forvum.sdk;

/**
 * SPI a channel plugin implements to bridge an external surface (TUI, Web, Telegram, ...) to the
 * agent runtime (ULTRAPLAN section 2.2, Layer 1). Sealed: third parties extend
 * {@link AbstractChannelProvider} rather than implementing this interface directly, which keeps the
 * set of channel implementations a closed, build-time-known set for native-image discovery.
 *
 * <p>The transport methods (inbound message delivery via {@code ai.forvum.core.ChannelMessage},
 * outbound rendering of the {@code AgentEvent} stream) are added by the channel milestones (M15-M17),
 * which bring the per-channel dependencies; M3 fixes only the structural contract and the id.
 */
public sealed interface ChannelProvider permits AbstractChannelProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();
}
