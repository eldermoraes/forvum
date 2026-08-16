package ai.forvum.engine.messaging;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.ChannelSender;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.MessageAccess;
import ai.forvum.sdk.OutputContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * The engine implementation of the #188 {@link MessageAccess} seam — the full {@code message.send}
 * security envelope, engine-side so no Layer-3 plugin can ship a variant that skips it:
 * <ol>
 *   <li><b>Destination allowlist (D4, fail-closed):</b> the channel AND resolved recipient must be
 *       allowlisted in {@code tools/message-send.json} ({@link MessageSendPolicy}); absent/empty
 *       refuses. A blank target resolves only to a SOLE allowlisted recipient — never to a channel-side
 *       arbitrary default.</li>
 *   <li><b>Installed sender resolution:</b> the {@link ChannelSender} matching the channel id, via CDI
 *       ({@code Instance}) from the app classpath — the engine stays extension-agnostic.</li>
 *   <li><b>Output guard on egress:</b> the text runs through the {@link OutputGuardChain} at the
 *       {@link HookLayer#PRE_TOOL_CALL} seam BEFORE any sender sees it — a Blocked disposition throws
 *       and the message never leaves the engine; a Redacted disposition sends the masked text.</li>
 * </ol>
 *
 * <p>The ToolExecutor's three gates (belt, {@code CHANNEL_SEND} scope, the {@code userConfirmRequired}
 * approval) run BEFORE this seam is ever reached; this class is the payload/destination envelope on top.
 * Runs blocking on the turn's virtual thread; no reflection.
 */
@ApplicationScoped
public class EngineMessageAccess implements MessageAccess {

    private final MessageSendPolicy policy;
    private final OutputGuardChain guards;
    private final Iterable<ChannelSender> senders;

    @Inject
    public EngineMessageAccess(MessageSendPolicy policy, OutputGuardChain guards,
            Instance<ChannelSender> senders) {
        this.policy = policy;
        this.guards = guards;
        this.senders = senders;
    }

    /** Package-private constructor wiring explicit collaborators — for tests. */
    EngineMessageAccess(MessageSendPolicy policy, OutputGuardChain guards, Iterable<ChannelSender> senders) {
        this.policy = policy;
        this.guards = guards;
        this.senders = senders;
    }

    @Override
    public String send(String channelId, String target, String text) {
        Set<String> allowed = policy.allowedRecipients(channelId);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException(
                    "message.send on channel '" + channelId + "' is not permitted: no recipient "
                  + "allowlist is configured (fail-closed). The operator enables it by listing "
                  + "recipients under the '" + channelId + "' key of $FORVUM_HOME/tools/"
                  + MessageSendPolicy.FILE_NAME + ".");
        }
        String resolved = policy.resolveTarget(channelId, target).orElseThrow(() ->
                new IllegalArgumentException(target == null || target.isBlank()
                        ? "message.send on channel '" + channelId + "' needs an explicit 'target': "
                        + allowed.size() + " recipients are allowlisted, so a blank target cannot "
                        + "resolve to a single destination."
                        : "message.send target is not an allowlisted recipient on channel '"
                        + channelId + "'. The operator manages the allowlist in $FORVUM_HOME/tools/"
                        + MessageSendPolicy.FILE_NAME + "."));

        ChannelSender sender = senderFor(channelId);
        if (sender == null) {
            throw new IllegalArgumentException(
                    "Channel '" + channelId + "' has no installed outbound sender — this build's '"
                  + channelId + "' channel does not support message.send.");
        }

        // #188: the guard chain sees the text BEFORE the sender — Blocked throws (the message never
        // leaves the engine), Redacted sends the masked text. PRE_TOOL_CALL is the egress-leaving-
        // the-engine-via-a-tool seam (reserved by DR-6a, wired here).
        AgentId agent = CurrentAgent.CURRENT_AGENT.isBound() ? CurrentAgent.CURRENT_AGENT.get() : null;
        String egress = guards.enforce(
                new OutputContext(HookLayer.PRE_TOOL_CALL, agent, CurrentAgent.currentTurnOrNull()), text);

        boolean delivered = sender.send(resolved, egress);
        if (!delivered) {
            return "Message NOT sent: channel '" + channelId + "' is not configured to send (it may be "
                  + "disabled or missing credentials in channels/" + channelId + ".json).";
        }
        return "Message sent to channel '" + channelId + "' (target " + resolved + ").";
    }

    private ChannelSender senderFor(String channelId) {
        for (ChannelSender candidate : senders) {
            if (candidate.extensionId().equals(channelId)) {
                return candidate;
            }
        }
        return null;
    }
}
