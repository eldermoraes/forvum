package ai.forvum.channel.whatsapp;

import ai.forvum.sdk.AbstractChannelProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Discovery marker for the WhatsApp channel (ULTRAPLAN §2.2, P2-CH). Per the M16 SPI decision,
 * {@link ai.forvum.sdk.ChannelProvider} stays a pure discovery marker ({@code extensionId()} only) — the
 * channel is self-driving: its inbound webhook ({@link WhatsAppWebhook}) drives turns through the SDK
 * {@link ai.forvum.sdk.ChannelTurnDriver}, so this type contributes no transport method. It carries
 * {@link ForvumExtension} + the {@code META-INF/forvum/plugin.json} manifest so the build-time plugin
 * scanner records the extension; {@code @ApplicationScoped} makes it CDI-discoverable.
 */
@ForvumExtension
@ApplicationScoped
public class WhatsAppChannelProvider extends AbstractChannelProvider {

    @Override
    public String extensionId() {
        return "whatsapp";
    }
}
