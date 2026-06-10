package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.signal.SignalChannelConfig.Spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code channels/signal.json} parsing and the {@link Spec} semantics: enabled-by-default, absent file
 * disabled, blank {@code baseUrl}/{@code account} treated as unset (warn + no-op upstream), and the
 * {@code allowedUserIds} allow-list (empty = any sender; ids match the sender's number OR uuid).
 */
class SignalChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    @Test
    void anAbsentFileIsDisabledWithNoCoordinates() {
        SignalChannelConfig config = new SignalChannelConfig(Path.of("/nonexistent/signal.json"));

        Spec spec = config.read();

        assertFalse(spec.enabled());
        assertTrue(spec.baseUrl().isEmpty());
        assertTrue(spec.account().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void aFullSpecParses(@TempDir Path home) throws IOException {
        Path file = Files.createDirectories(home.resolve("channels")).resolve("signal.json");
        Files.writeString(file, """
                { "baseUrl": "http://localhost:8080", "account": "+15559990000",
                  "allowedUserIds": ["+15550001111", "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111"] }
                """);

        Spec spec = new SignalChannelConfig(file).read();

        assertTrue(spec.enabled(), "a channel is enabled unless \"enabled\": false");
        assertEquals(Optional.of("http://localhost:8080"), spec.baseUrl());
        assertEquals(Optional.of("+15559990000"), spec.account());
        assertEquals(Set.of("+15550001111", "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111"),
                spec.allowedUserIds());
    }

    @Test
    void aMalformedFileThrowsForTheOperatorToSee(@TempDir Path home) throws IOException {
        Path file = Files.createDirectories(home.resolve("channels")).resolve("signal.json");
        Files.writeString(file, "{ not json");

        assertThrows(UncheckedIOException.class, () -> new SignalChannelConfig(file).read(),
                "a malformed config is a real misconfiguration, not silently swallowed");
    }

    @Test
    void explicitFalseDisables() {
        assertFalse(SignalChannelConfig.parse(json("{ \"enabled\": false }")).enabled());
    }

    @Test
    void blankCoordinatesAreTreatedAsUnset() {
        Spec spec = SignalChannelConfig.parse(
                json("{ \"baseUrl\": \"   \", \"account\": \"\" }"));

        assertTrue(spec.baseUrl().isEmpty());
        assertTrue(spec.account().isEmpty());
    }

    @Test
    void blankAllowedIdsAreDropped() {
        Spec spec = SignalChannelConfig.parse(
                json("{ \"allowedUserIds\": [\" +15550001111 \", \"\", \"  \"] }"));

        assertEquals(Set.of("+15550001111"), spec.allowedUserIds(), "ids are trimmed; blanks dropped");
    }

    @Test
    void anEmptyAllowListAllowsAnySender() {
        Spec spec = SignalChannelConfig.parse(json("{}"));

        assertTrue(spec.isSenderAllowed("+15550001111", null));
        assertTrue(spec.isSenderAllowed((String) null, null), "even an id-less sender (defensive)");
    }

    @Test
    void aNonEmptyAllowListRestrictsToListedIds() {
        Spec spec = SignalChannelConfig.parse(json(
                "{ \"allowedUserIds\": [\"+15550001111\", \"9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111\"] }"));

        assertTrue(spec.isSenderAllowed("+15550001111", null), "matched by number");
        assertTrue(spec.isSenderAllowed(null, "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111"), "matched by uuid");
        assertTrue(spec.isSenderAllowed("+15557772222", "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111"),
                "ANY listed id admits the sender");
        assertFalse(spec.isSenderAllowed("+15557772222", "0000-not-listed"));
        assertFalse(spec.isSenderAllowed((String) null, null));
    }

    @Test
    void resolveHomePrefersTheConfiguredHome() {
        assertEquals(Path.of("/custom/home").toAbsolutePath().normalize(),
                SignalChannelConfig.resolveHome(Optional.of("/custom/home"), "/users/me"));
        assertEquals(Path.of("/users/me/.forvum").toAbsolutePath().normalize(),
                SignalChannelConfig.resolveHome(Optional.empty(), "/users/me"));
        assertEquals(Path.of("/users/me/.forvum").toAbsolutePath().normalize(),
                SignalChannelConfig.resolveHome(Optional.of("  "), "/users/me"),
                "a blank configured home falls back to <user.home>/.forvum");
    }
}
