package ai.forvum.tools.messaging;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ChannelSender;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The #188 messaging tool provider: contributes the model-callable {@code message.send} to the engine's
 * global ToolRegistry (which discovers this {@code @ApplicationScoped} bean via CDI) and self-dispatches
 * it by name (M18 Option A, no reflection) to the installed {@link ChannelSender} matching the requested
 * channel id. The engine's ToolExecutor gates permission ({@code CHANNEL_SEND}) and audits every call;
 * this provider validates the channel id against the {@link ConfiguredChannels} oracle and dispatches an
 * already-permitted call.
 */
@ForvumExtension
@ApplicationScoped
public class MessagingToolProvider extends AbstractToolProvider {

    private final ConfiguredChannels channels;
    private final Iterable<ChannelSender> senders;

    @Inject
    public MessagingToolProvider(ConfiguredChannels channels, Instance<ChannelSender> senders) {
        this.channels = channels;
        this.senders = senders;
    }

    /** Package-private constructor wiring explicit collaborators — for tests. */
    MessagingToolProvider(ConfiguredChannels channels, Iterable<ChannelSender> senders) {
        this.channels = channels;
        this.senders = senders;
    }

    @Override
    public String extensionId() {
        return "messaging";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(MessageSendTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        if (!MessageSendTool.NAME.equals(toolName)) {
            throw new IllegalArgumentException(
                    "MessagingToolProvider does not contribute a tool named '" + toolName
                            + "'. It provides " + MessageSendTool.NAME + ".");
        }
        String channelId = stringArg(arguments, "channelId");
        String text = stringArg(arguments, "text");
        Object target = arguments.get("target");
        return MessageSendTool.send(channels.ids(), senders, channelId,
                target == null ? "" : target.toString(), text);
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a " + MessageSendTool.NAME + " call.");
        }
        return value.toString();
    }
}
