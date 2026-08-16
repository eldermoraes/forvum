package ai.forvum.tools.messaging;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.MessageAccess;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The #188 messaging tool provider (post-audit shape): contributes the model-callable
 * {@code message.send} to the engine's global ToolRegistry (which discovers this
 * {@code @ApplicationScoped} bean via CDI) and self-dispatches it by name (M18 Option A, no reflection)
 * to the engine's {@link MessageAccess} seam. The provider validates only argument SHAPE; the entire
 * security envelope — destination allowlist, sender resolution, output guards — is the engine's seam
 * implementation (Resolution B: a Layer-3 plugin cannot ship a variant that skips it). The engine's
 * ToolExecutor gates permission ({@code CHANNEL_SEND}) + the userConfirmRequired approval and audits
 * every call.
 */
@ForvumExtension
@ApplicationScoped
public class MessagingToolProvider extends AbstractToolProvider {

    private final MessageAccess messages;

    @Inject
    public MessagingToolProvider(MessageAccess messages) {
        this.messages = messages;
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
        return MessageSendTool.send(messages, channelId,
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
