package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.discord.DiscordChannelConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code DiscordChannelConfig} reads {@code channels/discord.json}: token + allowedUserIds parsing
 * (snowflakes as numbers or strings), the empty-list fail-closed default (#170: empty/absent denies every
 * user unless {@code allowAllUsers} opts into public mode), and the absent-file → empty/disabled spec (so
 * the channel boots gracefully with no {@code ~/.forvum/}). Plain POJO tests — no Quarkus boot needed.
 */
class DiscordChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTokenAndAllowedUserIds() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": true, \"botToken\": \"abc.123\", \"allowedUserIds\": [42, 99] }"));

        assertTrue(spec.enabled());
        assertEquals(Optional.of("abc.123"), spec.botToken());
        assertEquals(Set.of(42L, 99L), spec.allowedUserIds());
    }

    @Test
    void parsesSnowflakeIdsGivenAsStrings() throws Exception {
        // Discord snowflakes are 64-bit; operators commonly paste them as JSON strings.
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [\"123456789012345678\"] }"));

        assertEquals(Set.of(123456789012345678L), spec.allowedUserIds());
        assertTrue(spec.isUserAllowed(123456789012345678L));
    }

    @Test
    void emptyAllowListDeniesAnyUser() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [] }"));

        assertFalse(spec.isUserAllowed(1L), "#170 fail-closed: an empty allow-list denies every user");
        assertFalse(spec.isUserAllowed(999_999L), "#170 fail-closed");
    }

    @Test
    void absentAllowListDeniesAnyUser() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"t\" }"));

        assertFalse(spec.isUserAllowed(7L), "#170 fail-closed: an absent allow-list denies every user");
    }

    @Test
    void allowAllUsersOptsIntoPublicModeWhenAllowListEmpty() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [], \"allowAllUsers\": true }"));

        assertTrue(spec.isUserAllowed(1L), "an explicit allowAllUsers admits any user (#170 public mode)");
        assertTrue(spec.isUserAllowed(999_999L));
    }

    @Test
    void nonEmptyAllowListRestrictsToListedIds() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [42] }"));

        assertTrue(spec.isUserAllowed(42L));
        assertFalse(spec.isUserAllowed(7L), "a user not in the allow-list is refused");
    }

    @Test
    void enabledDefaultsTrueWhenAbsentButFalseWhenSet() throws Exception {
        assertTrue(DiscordChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"t\" }")).enabled());
        assertFalse(DiscordChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"botToken\": \"t\" }")).enabled());
    }

    @Test
    void blankTokenIsTreatedAsAbsent() throws Exception {
        Spec spec = DiscordChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"  \" }"));

        assertTrue(spec.botToken().isEmpty(), "a blank botToken must be treated as unset");
    }

    @Test
    void absentFileReadsAsEmptyDisabledSpec(@org.junit.jupiter.api.io.TempDir Path dir) {
        DiscordChannelConfig config = new DiscordChannelConfig(dir.resolve("discord.json"));
        Spec spec = config.read();

        assertFalse(spec.enabled(), "an absent config file disables the channel");
        assertTrue(spec.botToken().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void readsAnExistingFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("discord.json");
        Files.writeString(file, "{ \"botToken\": \"live.token\", \"allowedUserIds\": [5] }");

        Spec spec = new DiscordChannelConfig(file).read();

        assertEquals(Optional.of("live.token"), spec.botToken());
        assertEquals(Set.of(5L), spec.allowedUserIds());
    }
}
