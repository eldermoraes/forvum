package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.slack.SlackChannelConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code SlackChannelConfig} reads {@code channels/slack.json}: the two-token parsing (botToken +
 * appToken, BOTH required to serve), allowedUserIds as opaque Slack user-id strings, the empty-list
 * fail-closed convention (#170, public-mode opt-in via {@code allowAllUsers}), and the absent-file →
 * empty/disabled spec (so the channel boots gracefully with no {@code ~/.forvum/}). Plain POJO tests —
 * no Quarkus boot needed.
 */
class SlackChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesBothTokensAndAllowedUserIds() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": true, \"botToken\": \"xoxb-1-abc\", \"appToken\": \"xapp-1-A1-def\","
                        + " \"allowedUserIds\": [\"U42\", \"U99\"] }"));

        assertTrue(spec.enabled());
        assertEquals(Optional.of("xoxb-1-abc"), spec.botToken());
        assertEquals(Optional.of("xapp-1-A1-def"), spec.appToken());
        assertEquals(Set.of("U42", "U99"), spec.allowedUserIds());
    }

    @Test
    void emptyAllowListDeniesAnyUser() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\", \"allowedUserIds\": [] }"));

        assertFalse(spec.isUserAllowed("U1"), "an empty allow-list now denies every user (#170 fail-closed)");
        assertFalse(spec.isUserAllowed("UANY"));
    }

    @Test
    void absentAllowListDeniesAnyUser() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\" }"));

        assertFalse(spec.isUserAllowed("U7"), "an absent allow-list now denies every user (#170 fail-closed)");
    }

    @Test
    void allowAllUsersAdmitsAnyUser() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\", \"allowAllUsers\": true }"));

        assertTrue(spec.isUserAllowed("U1"), "explicit allowAllUsers admits any user");
        assertTrue(spec.isUserAllowed("UANY"));
    }

    @Test
    void nonEmptyAllowListRestrictsToListedIds() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\", \"allowedUserIds\": [\"U42\"] }"));

        assertTrue(spec.isUserAllowed("U42"));
        assertFalse(spec.isUserAllowed("U7"), "a user not in the allow-list is refused");
    }

    @Test
    void allowedUserIdsAreTrimmedAndBlankEntriesDropped() throws Exception {
        Spec spec = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"appToken\": \"a\","
                        + " \"allowedUserIds\": [\" U42 \", \"\", \"  \"] }"));

        assertEquals(Set.of("U42"), spec.allowedUserIds());
        assertTrue(spec.isUserAllowed("U42"));
    }

    @Test
    void enabledDefaultsTrueWhenAbsentButFalseWhenSet() throws Exception {
        assertTrue(SlackChannelConfig.parse(
                MAPPER.readTree("{ \"botToken\": \"t\", \"appToken\": \"a\" }")).enabled());
        assertFalse(SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"botToken\": \"t\", \"appToken\": \"a\" }")).enabled());
    }

    @Test
    void blankOrMissingTokensAreTreatedAsAbsent() throws Exception {
        Spec blankBot = SlackChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"  \", \"appToken\": \"xapp-1\" }"));
        assertTrue(blankBot.botToken().isEmpty(), "a blank botToken must be treated as unset");
        assertEquals(Optional.of("xapp-1"), blankBot.appToken());

        Spec missingApp = SlackChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"xoxb-1\" }"));
        assertEquals(Optional.of("xoxb-1"), missingApp.botToken());
        assertTrue(missingApp.appToken().isEmpty(), "a missing appToken must be treated as unset");
    }

    @Test
    void absentFileReadsAsEmptyDisabledSpec(@org.junit.jupiter.api.io.TempDir Path dir) {
        SlackChannelConfig config = new SlackChannelConfig(dir.resolve("slack.json"));
        Spec spec = config.read();

        assertFalse(spec.enabled(), "an absent config file disables the channel");
        assertTrue(spec.botToken().isEmpty());
        assertTrue(spec.appToken().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void aMalformedFileThrowsAnUncheckedIOExceptionTheOperatorSees(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("slack.json");
        Files.writeString(file, "this is not json");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> new SlackChannelConfig(file).read(),
                "a malformed config is a real misconfiguration, not silently swallowed");
    }

    @Test
    void resolveHomePrefersTheConfiguredHomeAndFallsBackToUserHome() {
        assertEquals(Path.of("/forvum/home").toAbsolutePath().normalize(),
                SlackChannelConfig.resolveHome(Optional.of("/forvum/home"), "/home/u"));
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(),
                SlackChannelConfig.resolveHome(Optional.empty(), "/home/u"));
        assertEquals(Path.of("/home/u/.forvum").toAbsolutePath().normalize(),
                SlackChannelConfig.resolveHome(Optional.of("  "), "/home/u"),
                "a blank configured home falls back to <user.home>/.forvum");
    }

    @Test
    void productionConstructorResolvesChannelsSlackJsonUnderTheConfiguredHome(
            @org.junit.jupiter.api.io.TempDir Path home) throws Exception {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("slack.json"),
                "{ \"botToken\": \"xoxb-h\", \"appToken\": \"xapp-h\" }");

        Spec spec = new SlackChannelConfig(Optional.of(home.toString())).read();

        assertEquals(Optional.of("xoxb-h"), spec.botToken());
        assertEquals(Optional.of("xapp-h"), spec.appToken());
    }

    @Test
    void readsAnExistingFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("slack.json");
        Files.writeString(file,
                "{ \"botToken\": \"xoxb-live\", \"appToken\": \"xapp-live\","
                        + " \"allowedUserIds\": [\"U5\"] }");

        Spec spec = new SlackChannelConfig(file).read();

        assertEquals(Optional.of("xoxb-live"), spec.botToken());
        assertEquals(Optional.of("xapp-live"), spec.appToken());
        assertEquals(Set.of("U5"), spec.allowedUserIds());
    }
}
