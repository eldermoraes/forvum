package ai.forvum.engine.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.config.ConfigLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MessageSendPolicy} — the #188 fail-closed destination allowlist (D4): an absent file, an
 * absent channel key, or an empty recipient array all refuse; a blank target resolves ONLY to a sole
 * allowlisted recipient; structural violations throw the doctor-shared {@link IllegalStateException}.
 * Pure unit test through the package-private explicit-file constructor — no CDI, no Quarkus.
 */
class MessageSendPolicyTest {

    @TempDir
    Path dir;

    private MessageSendPolicy policyWith(String json) throws IOException {
        Path file = dir.resolve("message-send.json");
        Files.writeString(file, json);
        return new MessageSendPolicy(new ConfigLoader(new ObjectMapper()), file);
    }

    @Test
    void anAbsentFileRefusesEverySend() {
        MessageSendPolicy policy = new MessageSendPolicy(
                new ConfigLoader(new ObjectMapper()), dir.resolve("message-send.json"));

        assertEquals(Set.of(), policy.allowedRecipients("telegram"),
                "#188 fail-closed: no file means message.send is not permitted anywhere");
        assertEquals(Optional.empty(), policy.resolveTarget("telegram", "42"));
    }

    @Test
    void anAbsentChannelKeyOrEmptyArrayRefuses() throws IOException {
        MessageSendPolicy policy = policyWith("""
                {"telegram": [], "slack": ["U1"]}""");

        assertEquals(Set.of(), policy.allowedRecipients("telegram"), "empty array: fail-closed");
        assertEquals(Set.of(), policy.allowedRecipients("discord"), "absent key: fail-closed");
        assertEquals(Set.of("U1"), policy.allowedRecipients("slack"));
    }

    @Test
    void anExplicitTargetResolvesOnlyWhenItIsAMember() throws IOException {
        MessageSendPolicy policy = policyWith("""
                {"telegram": ["42", "43"]}""");

        assertEquals(Optional.of("42"), policy.resolveTarget("telegram", "42"));
        assertEquals(Optional.empty(), policy.resolveTarget("telegram", "999"),
                "a non-member explicit target is refused");
    }

    @Test
    void aBlankTargetResolvesToTheSoleRecipientAndOnlyThen() throws IOException {
        MessageSendPolicy sole = policyWith("""
                {"telegram": ["42"]}""");
        assertEquals(Optional.of("42"), sole.resolveTarget("telegram", ""));
        assertEquals(Optional.of("42"), sole.resolveTarget("telegram", null));

        MessageSendPolicy several = policyWith("""
                {"telegram": ["42", "43"]}""");
        assertEquals(Optional.empty(), several.resolveTarget("telegram", " "),
                "blank + multiple recipients is ambiguous — refused, never a channel-side default");
    }

    @Test
    void structuralViolationsThrowTheDoctorSharedException() throws IOException {
        assertThrows(IllegalStateException.class,
                () -> policyWith("""
                        ["telegram"]""").allowedRecipients("telegram"),
                "non-object root");
        assertThrows(IllegalStateException.class,
                () -> policyWith("""
                        {"telegram": "42"}""").allowedRecipients("telegram"),
                "non-array channel value");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> policyWith("""
                        {"telegram": [42]}""").allowedRecipients("telegram"),
                "non-string recipient entry");
        assertTrue(e.getMessage().contains("telegram"));
    }
}
