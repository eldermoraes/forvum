package ai.forvum.channel.web;

import ai.forvum.sdk.AbstractChannelProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Discovery marker for the Web channel (ULTRAPLAN section 2.2, the M16 Channel-Fleet anchor). Per the
 * M16 SPI decision, {@link ai.forvum.sdk.ChannelProvider} stays a pure discovery marker
 * ({@code extensionId()} only) — the channel is self-driving: its WebSocket endpoint drives turns
 * through the SDK {@link ai.forvum.sdk.ChannelTurnDriver}, so this type contributes no transport
 * method. It carries {@link ForvumExtension} + the {@code META-INF/forvum/plugin.json} manifest so the
 * build-time plugin scanner (a later milestone) records the extension; {@code @ApplicationScoped} makes
 * it CDI-discoverable.
 */
@ForvumExtension
@ApplicationScoped
public class WebChannelProvider extends AbstractChannelProvider {

    @Override
    public String extensionId() {
        return "web";
    }
}
