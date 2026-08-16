package ai.forvum.tools.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MessageAccess;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link MessagingToolProvider} contract (#188, post-audit): it contributes {@code message.send} —
 * CHANNEL_SEND scope AND {@code userConfirmRequired} (the owner approves each send) — validates only
 * argument shape, and delegates every call to the engine's {@link MessageAccess} seam, which owns the
 * whole security envelope (destination allowlist, sender resolution, output guards). Pure unit test with
 * a recording {@code MessageAccess} double — no engine, no CDI container.
 */
class MessagingToolProviderTest {

    /** A recording {@link MessageAccess} double. */
    static final class RecordingAccess implements MessageAccess {
        final List<String[]> calls = new ArrayList<>();
        String reply = "Message sent to channel 'telegram' (target 42).";

        @Override
        public String send(String channelId, String target, String text) {
            calls.add(new String[] {channelId, target, text});
            return reply;
        }
    }

    @Test
    void contributesMessageSendWithChannelSendScopeAndUserConfirm() {
        MessagingToolProvider provider = new MessagingToolProvider(new RecordingAccess());

        assertEquals("messaging", provider.extensionId());
        List<ToolSpec> tools = provider.tools();
        assertEquals(1, tools.size());
        ToolSpec spec = tools.getFirst();
        assertEquals("message.send", spec.name());
        assertEquals(PermissionScope.CHANNEL_SEND, spec.requiredScope());
        assertTrue(spec.userConfirmRequired(),
                "message.send must be userConfirmRequired — the P2-14 approval gate is part of the "
                        + "#188 security envelope");
        assertTrue(spec.parametersJsonSchema().contains("channelId"));
    }

    @Test
    void delegatesTheCallToTheMessageAccessSeam() {
        RecordingAccess access = new RecordingAccess();
        MessagingToolProvider provider = new MessagingToolProvider(access);

        String reply = provider.invoke("message.send",
                Map.of("channelId", "telegram", "target", "42", "text", "hello"));

        assertEquals(access.reply, reply);
        assertEquals(1, access.calls.size());
        assertEquals("telegram", access.calls.getFirst()[0]);
        assertEquals("42", access.calls.getFirst()[1]);
        assertEquals("hello", access.calls.getFirst()[2]);
    }

    @Test
    void omittedTargetIsPassedAsEmptyForSoleRecipientResolution() {
        RecordingAccess access = new RecordingAccess();
        MessagingToolProvider provider = new MessagingToolProvider(access);

        provider.invoke("message.send", Map.of("channelId", "telegram", "text", "hi"));

        assertEquals("", access.calls.getFirst()[1]);
    }

    @Test
    void rejectsAnUnknownToolName() {
        MessagingToolProvider provider = new MessagingToolProvider(new RecordingAccess());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.broadcast", Map.of()));
        assertTrue(e.getMessage().contains("message.send"));
    }

    @Test
    void rejectsMissingOrBlankRequiredArguments() {
        RecordingAccess access = new RecordingAccess();
        MessagingToolProvider provider = new MessagingToolProvider(access);

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send", Map.of("text", "hi")));
        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("message.send", Map.of("channelId", "telegram", "text", " ")));
        assertTrue(access.calls.isEmpty(), "shape rejection must never reach the seam");
    }
}
