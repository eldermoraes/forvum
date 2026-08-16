package ai.forvum.tools.messaging;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MessageAccess;

/**
 * The {@code message.send} tool (#188, post-audit shape): holds the ToolSpec and delegates the delivery
 * to the engine's {@link MessageAccess} seam, which owns the WHOLE security envelope — the fail-closed
 * destination allowlist ({@code tools/message-send.json}), the installed-sender resolution, and the
 * output-guard chain over the egress text. The spec is {@code userConfirmRequired} (the P2-14 approval
 * gate: the owner confirms each send) on top of belt membership + the
 * {@link PermissionScope#CHANNEL_SEND} RBAC scope the engine's ToolExecutor enforces and audits.
 */
final class MessageSendTool {

    static final String NAME = "message.send";

    static final ToolSpec SPEC = new ToolSpec(
            NAME,
            "Send a text message to a user on a configured channel (e.g. telegram). 'channelId' names "
                    + "the channel; 'target' is the channel-specific destination and must be an "
                    + "operator-allowlisted recipient (empty is accepted only when exactly one recipient "
                    + "is allowlisted); 'text' is the message to deliver. Sends are refused unless the "
                    + "operator has allowlisted the destination in tools/message-send.json.",
            PermissionScope.CHANNEL_SEND,
            """
            {"type":"object","properties":{\
            "channelId":{"type":"string","description":"The configured channel id (e.g. telegram)."},\
            "target":{"type":"string","description":"An allowlisted channel-specific destination; empty only when a single recipient is allowlisted."},\
            "text":{"type":"string","description":"The message text to deliver."}},\
            "required":["channelId","text"]}""",
            true);

    private MessageSendTool() {
    }

    /** Delegate the already-permitted call to the engine's security envelope. */
    static String send(MessageAccess messages, String channelId, String target, String text) {
        return messages.send(channelId, target, text);
    }
}
