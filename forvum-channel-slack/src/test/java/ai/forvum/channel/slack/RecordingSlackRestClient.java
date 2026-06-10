package ai.forvum.channel.slack;

import ai.forvum.channel.slack.dto.ChatPostMessage;
import ai.forvum.channel.slack.dto.ChatPostMessageResponse;
import ai.forvum.channel.slack.dto.ConnectionsOpenResponse;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@link SlackRestClient}: {@code postMessage} records every outbound
 * {@code (authorization, channel, text)} and answers with a configurable {@code ok}/{@code error};
 * {@code connectionsOpen} answers a canned response. Used directly (not as a CDI bean) so
 * {@code SlackMessageProcessor.process} can be driven without a live Slack Web API.
 */
class RecordingSlackRestClient implements SlackRestClient {

    final CopyOnWriteArrayList<Posted> posted = new CopyOnWriteArrayList<>();

    /** The reply every {@code postMessage} returns; tests flip it to exercise the ok=false arm. */
    volatile ChatPostMessageResponse postMessageResponse = new ChatPostMessageResponse(true, null);

    record Posted(String authorization, String channel, String text) {
    }

    @Override
    public ConnectionsOpenResponse connectionsOpen(String authorization) {
        return new ConnectionsOpenResponse(true, "wss://wss-primary.slack.com/link/?ticket=t", null);
    }

    @Override
    public ChatPostMessageResponse postMessage(String authorization, ChatPostMessage body) {
        posted.add(new Posted(authorization, body.channel(), body.text()));
        return postMessageResponse;
    }
}
