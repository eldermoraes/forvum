package ai.forvum.engine.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.security.FilteringOutcome;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.AbstractOutputGuard;
import ai.forvum.sdk.ChannelSender;
import ai.forvum.sdk.OutputContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EngineMessageAccess} — the #188 {@code message.send} security envelope: fail-closed allowlist
 * denial, blank-target ambiguity refusal, output-guard enforcement BEFORE the sender (Blocked → the
 * sender is never called; Redacted → the masked text is what leaves), and the happy path. Pure unit
 * test through the package-private explicit-collaborator constructors — no CDI, no Quarkus.
 */
class EngineMessageAccessTest {

    @TempDir
    Path dir;

    /** A recording {@link ChannelSender} double. */
    static final class RecordingSender implements ChannelSender {
        final List<String[]> sent = new ArrayList<>();
        boolean configured = true;

        @Override
        public String extensionId() {
            return "telegram";
        }

        @Override
        public boolean send(String target, String text) {
            if (!configured) {
                return false;
            }
            sent.add(new String[] {target, text});
            return true;
        }
    }

    /** An always-Blocked guard. */
    static final class BlockAll extends AbstractOutputGuard {
        @Override
        public FilteringOutcome filter(OutputContext ctx, String candidate) {
            return new FilteringOutcome.Blocked("test policy block");
        }
    }

    /** A redacting guard. */
    static final class MaskAll extends AbstractOutputGuard {
        @Override
        public FilteringOutcome filter(OutputContext ctx, String candidate) {
            return new FilteringOutcome.Redacted("[masked]", 1);
        }
    }

    private MessageSendPolicy policyWith(String json) throws IOException {
        Path file = dir.resolve("message-send.json");
        Files.writeString(file, json);
        return new MessageSendPolicy(new ConfigLoader(new ObjectMapper()), file);
    }

    private static EngineMessageAccess access(MessageSendPolicy policy, OutputGuardChain guards,
            ChannelSender... senders) {
        return new EngineMessageAccess(policy, guards, List.of(senders));
    }

    @Test
    void anUnconfiguredChannelIsRefusedFailClosed() {
        MessageSendPolicy policy = new MessageSendPolicy(
                new ConfigLoader(new ObjectMapper()), dir.resolve("message-send.json"));
        RecordingSender sender = new RecordingSender();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> access(policy, new OutputGuardChain(List.of()), sender).send("telegram", "42", "hi"));

        assertTrue(e.getMessage().contains("fail-closed"));
        assertTrue(sender.sent.isEmpty(), "a refused send must never reach a sender");
    }

    @Test
    void aNonAllowlistedTargetIsRefused() throws IOException {
        RecordingSender sender = new RecordingSender();

        assertThrows(IllegalArgumentException.class,
                () -> access(policyWith("""
                        {"telegram": ["42"]}"""), new OutputGuardChain(List.of()), sender)
                        .send("telegram", "999", "hi"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aBlankTargetWithMultipleRecipientsIsRefused() throws IOException {
        RecordingSender sender = new RecordingSender();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> access(policyWith("""
                        {"telegram": ["42", "43"]}"""), new OutputGuardChain(List.of()), sender)
                        .send("telegram", "", "hi"));

        assertTrue(e.getMessage().contains("explicit 'target'"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aGuardBlockedTextNeverReachesTheSender() throws IOException {
        RecordingSender sender = new RecordingSender();
        OutputGuardChain guards = new OutputGuardChain(List.of(new BlockAll()));

        assertThrows(OutputFilteredException.class,
                () -> access(policyWith("""
                        {"telegram": ["42"]}"""), guards, sender).send("telegram", "42", "secret"));

        assertTrue(sender.sent.isEmpty(),
                "#188: the guard chain runs BEFORE the sender — Blocked means the message never leaves");
    }

    @Test
    void aRedactedTextIsWhatLeavesTheEngine() throws IOException {
        RecordingSender sender = new RecordingSender();
        OutputGuardChain guards = new OutputGuardChain(List.of(new MaskAll()));

        String reply = access(policyWith("""
                {"telegram": ["42"]}"""), guards, sender).send("telegram", "42", "token=abc");

        assertEquals("[masked]", sender.sent.getFirst()[1], "the sender sees the guarded text only");
        assertTrue(reply.contains("Message sent"));
    }

    @Test
    void happyPathSendsToTheResolvedSoleRecipient() throws IOException {
        RecordingSender sender = new RecordingSender();

        String reply = access(policyWith("""
                {"telegram": ["42"]}"""), new OutputGuardChain(List.of()), sender)
                .send("telegram", "", "hello");

        assertEquals("42", sender.sent.getFirst()[0], "blank target resolved to the sole recipient");
        assertEquals("hello", sender.sent.getFirst()[1]);
        assertTrue(reply.contains("target 42"));
    }

    @Test
    void aChannelWithNoInstalledSenderIsReported() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> access(policyWith("""
                        {"discord": ["u1"]}"""), new OutputGuardChain(List.of()))
                        .send("discord", "u1", "hi"));
        assertTrue(e.getMessage().contains("no installed outbound sender"));
    }

    @Test
    void anUnconfiguredSenderIsReportedNotThrown() throws IOException {
        RecordingSender sender = new RecordingSender();
        sender.configured = false;

        String reply = access(policyWith("""
                {"telegram": ["42"]}"""), new OutputGuardChain(List.of()), sender)
                .send("telegram", "42", "hi");

        assertTrue(reply.contains("NOT sent"));
    }
}
