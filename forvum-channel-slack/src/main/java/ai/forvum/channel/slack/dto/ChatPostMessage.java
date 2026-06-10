package ai.forvum.channel.slack.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The request body for the Slack Web API {@code POST /chat.postMessage} endpoint: a JSON object
 * {@code { "channel": "<conversation id>", "text": "<text>" }}. Forvum sends only the plain text reply
 * (no blocks, no thread_ts) in v0.1 — threaded replies are a documented follow-up. An OUTBOUND
 * Jackson-serialized record, so it carries a real {@code RegisterForReflection} (the Discord
 * NATIVE-FRAME lesson applies to REST bodies too) and is pinned by an encode-path test.
 */
@RegisterForReflection
public record ChatPostMessage(String channel, String text) {
}
