package ai.forvum.tools.messaging;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.ChannelSender;

import java.util.Set;

/**
 * The {@code message.send} tool (#188): pushes an outbound text to a configured channel through the SDK
 * {@link ChannelSender} SPI. Gated by belt membership + the {@link PermissionScope#CHANNEL_SEND} RBAC
 * scope (the engine's ToolExecutor enforces both and audits every call); this class validates the
 * arguments — the channel id must name a configured {@code channels/<id>.json} (the
 * {@link ConfiguredChannels} oracle) — resolves the installed sender by {@link ChannelSender#extensionId()},
 * and reports the outcome to the model.
 */
final class MessageSendTool {

    static final String NAME = "message.send";

    static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Send a text message to a user on a configured channel (e.g. telegram). 'channelId' names "
                    + "the channel (a configured channels/<id>.json); 'target' is the channel-specific "
                    + "destination (for telegram, a chat id in decimal form; empty string uses the "
                    + "channel's configured default destination); 'text' is the message to deliver.",
            PermissionScope.CHANNEL_SEND,
            """
            {"type":"object","properties":{\
            "channelId":{"type":"string","description":"The configured channel id (e.g. telegram)."},\
            "target":{"type":"string","description":"Channel-specific destination; empty for the channel default."},\
            "text":{"type":"string","description":"The message text to deliver."}},\
            "required":["channelId","text"]}""");

    private MessageSendTool() {
    }

    /**
     * Validate and deliver: an unknown channel id (not among {@code configuredIds}) or an absent sender
     * for a known channel is rejected with {@link IllegalArgumentException} (the engine audits the call
     * {@code error}); a sender that reports itself unconfigured ({@code send(...) == false}) yields an
     * actionable message for the model rather than a delivery claim.
     */
    static String send(Set<String> configuredIds, Iterable<ChannelSender> senders,
                       String channelId, String target, String text) {
        if (!configuredIds.contains(channelId)) {
            throw new IllegalArgumentException(
                    "Unknown channel '" + channelId + "'. Configured channels: "
                            + (configuredIds.isEmpty() ? "(none)" : String.join(", ", configuredIds))
                            + ". A channel is configured by a $FORVUM_HOME/channels/<id>.json file.");
        }
        ChannelSender sender = null;
        for (ChannelSender candidate : senders) {
            if (candidate.extensionId().equals(channelId)) {
                sender = candidate;
                break;
            }
        }
        if (sender == null) {
            throw new IllegalArgumentException(
                    "Channel '" + channelId + "' is configured but has no installed outbound sender — "
                            + "this build's '" + channelId + "' channel does not support message.send.");
        }
        boolean delivered = sender.send(target, text);
        if (!delivered) {
            return "Message NOT sent: channel '" + channelId + "' is not configured to send (it may be "
                    + "disabled, missing credentials, or lack a default destination in channels/"
                    + channelId + ".json).";
        }
        return "Message sent to channel '" + channelId + "'"
                + (target == null || target.isBlank() ? " (default destination)." : " (target " + target + ").");
    }
}
