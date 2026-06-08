package ai.forvum.channel.discord;

import ai.forvum.sdk.AbstractChannelProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Discovery marker for the Discord channel (P2-CH, the first additional first-party channel). Per the M16
 * SPI decision, {@link ai.forvum.sdk.ChannelProvider} stays a pure discovery marker
 * ({@code extensionId()} only) — the channel is self-driving: its gateway client ({@link DiscordChannel}
 * → {@link DiscordGatewayEndpoint}) drives turns through the SDK {@link ai.forvum.sdk.ChannelTurnDriver},
 * so this type contributes no transport method. It carries {@link ForvumExtension} + the
 * {@code META-INF/forvum/plugin.json} manifest so the build-time plugin scanner records the extension;
 * {@code @ApplicationScoped} makes it CDI-discoverable.
 */
@ForvumExtension
@ApplicationScoped
public class DiscordChannelProvider extends AbstractChannelProvider {

    @Override
    public String extensionId() {
        return "discord";
    }
}
