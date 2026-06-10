package ai.forvum.channel.signal;

import ai.forvum.sdk.AbstractChannelProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Discovery marker for the Signal channel (ULTRAPLAN §2.2 / P2-CH). Per the M16 SPI decision,
 * {@link ai.forvum.sdk.ChannelProvider} stays a pure discovery marker ({@code extensionId()} only) — the
 * channel is self-driving: its SSE worker ({@link SignalChannel}) drives turns through the SDK
 * {@link ai.forvum.sdk.ChannelTurnDriver}, so this type contributes no transport method. It carries
 * {@link ForvumExtension} + the {@code META-INF/forvum/plugin.json} manifest so the build-time plugin
 * scanner records the extension; {@code @ApplicationScoped} makes it CDI-discoverable.
 */
@ForvumExtension
@ApplicationScoped
public class SignalChannelProvider extends AbstractChannelProvider {

    @Override
    public String extensionId() {
        return "signal";
    }
}
