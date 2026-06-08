package ai.forvum.channel.discord;

import ai.forvum.channel.discord.dto.CreateMessage;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link DiscordRestClient}: {@code createMessage} records every outbound
 * {@code (authorization, channelId, content)}. Used directly (not as a CDI bean) so
 * {@code MessageProcessor.process} can be driven without a live Discord REST endpoint.
 */
class RecordingDiscordRestClient implements DiscordRestClient {

    final CopyOnWriteArrayList<Posted> posted = new CopyOnWriteArrayList<>();

    record Posted(String authorization, String channelId, String content) {
    }

    @Override
    public void createMessage(String authorization, String channelId, CreateMessage body) {
        posted.add(new Posted(authorization, channelId, body.content()));
    }
}
