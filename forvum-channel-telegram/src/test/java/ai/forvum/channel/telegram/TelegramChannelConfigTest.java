package ai.forvum.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code TelegramChannelConfig} reads {@code channels/telegram.json}: token + allowedUserIds parsing, the
 * empty-list "allow any" convention, and the absent-file → empty/disabled spec (so the channel boots
 * gracefully with no {@code ~/.forvum/}). Plain POJO tests — no Quarkus boot needed.
 */
class TelegramChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTokenAndAllowedUserIds() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": true, \"botToken\": \"abc:123\", \"allowedUserIds\": [42, 99] }"));

        assertTrue(spec.enabled());
        assertEquals(Optional.of("abc:123"), spec.botToken());
        assertEquals(Set.of(42L, 99L), spec.allowedUserIds());
    }

    @Test
    void emptyAllowListAllowsAnyUser() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [] }"));

        assertTrue(spec.isUserAllowed(1L), "an empty allow-list permits any user");
        assertTrue(spec.isUserAllowed(999_999L));
    }

    @Test
    void absentAllowListAllowsAnyUser() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"t\" }"));

        assertTrue(spec.isUserAllowed(7L), "an absent allow-list permits any user");
    }

    @Test
    void nonEmptyAllowListRestrictsToListedIds() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [42] }"));

        assertTrue(spec.isUserAllowed(42L));
        assertFalse(spec.isUserAllowed(7L), "a user not in the allow-list is refused");
    }

    @Test
    void enabledDefaultsTrueWhenAbsentButFalseWhenSet() throws Exception {
        assertTrue(TelegramChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"t\" }")).enabled());
        assertFalse(TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"botToken\": \"t\" }")).enabled());
    }

    @Test
    void blankTokenIsTreatedAsAbsent() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"  \" }"));

        assertTrue(spec.botToken().isEmpty(), "a blank botToken must be treated as unset");
    }

    @Test
    void absentFileReadsAsEmptyDisabledSpec(@org.junit.jupiter.api.io.TempDir Path dir) {
        TelegramChannelConfig config = new TelegramChannelConfig(dir.resolve("telegram.json"));
        Spec spec = config.read();

        assertFalse(spec.enabled(), "an absent config file disables the channel");
        assertTrue(spec.botToken().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void readsAnExistingFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("telegram.json");
        Files.writeString(file, "{ \"botToken\": \"live:token\", \"allowedUserIds\": [5] }");

        Spec spec = new TelegramChannelConfig(file).read();

        assertEquals(Optional.of("live:token"), spec.botToken());
        assertEquals(Set.of(5L), spec.allowedUserIds());
    }
}
