package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * {@code MatrixChannelConfig} reads {@code channels/matrix.json}: homeserver/accessToken/userId +
 * allowedUserIds parsing, the empty-list "allow any" convention, and the absent-file → empty/disabled
 * spec (so the channel boots gracefully with no {@code ~/.forvum/}). Plain POJO tests — no Quarkus boot
 * needed.
 */
class MatrixChannelConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesHomeserverTokenUserIdAndAllowedUserIds() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree("""
                { "enabled": true, "homeserver": "https://matrix.example.org",
                  "accessToken": "syt_secret", "userId": "@bot:example.org",
                  "allowedUserIds": ["@alice:example.org", "@bob:example.org"] }"""));

        assertTrue(spec.enabled());
        assertEquals(Optional.of("https://matrix.example.org"), spec.homeserver());
        assertEquals(Optional.of("syt_secret"), spec.accessToken());
        assertEquals(Optional.of("@bot:example.org"), spec.userId());
        assertEquals(Set.of("@alice:example.org", "@bob:example.org"), spec.allowedUserIds());
    }

    @Test
    void emptyAllowListAllowsAnyUser() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"homeserver\": \"https://m.org\", \"accessToken\": \"t\", \"allowedUserIds\": [] }"));

        assertTrue(spec.isUserAllowed("@anyone:example.org"), "an empty allow-list permits any user");
    }

    @Test
    void absentAllowListAllowsAnyUser() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"homeserver\": \"https://m.org\", \"accessToken\": \"t\" }"));

        assertTrue(spec.isUserAllowed("@anyone:example.org"), "an absent allow-list permits any user");
    }

    @Test
    void nonEmptyAllowListRestrictsToListedIds() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"accessToken\": \"t\", \"allowedUserIds\": [\"@alice:example.org\"] }"));

        assertTrue(spec.isUserAllowed("@alice:example.org"));
        assertFalse(spec.isUserAllowed("@mallory:example.org"),
                "a user not in the allow-list is refused");
    }

    @Test
    void enabledDefaultsTrueWhenAbsentButFalseWhenSet() throws Exception {
        assertTrue(MatrixChannelConfig.parse(MAPPER.readTree("{ \"accessToken\": \"t\" }")).enabled());
        assertFalse(MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"accessToken\": \"t\" }")).enabled());
    }

    @Test
    void blankCredentialFieldsAreTreatedAsAbsent() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"homeserver\": \"  \", \"accessToken\": \"\", \"userId\": \"   \" }"));

        assertTrue(spec.homeserver().isEmpty(), "a blank homeserver must be treated as unset");
        assertTrue(spec.accessToken().isEmpty(), "a blank accessToken must be treated as unset");
        assertTrue(spec.userId().isEmpty(), "a blank userId must be treated as unset");
    }

    @Test
    void blankAllowListEntriesAreDropped() throws Exception {
        Spec spec = MatrixChannelConfig.parse(MAPPER.readTree(
                "{ \"allowedUserIds\": [\" @alice:example.org \", \"  \", \"\"] }"));

        assertEquals(Set.of("@alice:example.org"), spec.allowedUserIds(),
                "entries are trimmed and blank entries dropped");
    }

    @Test
    void absentFileReadsAsEmptyDisabledSpec(@TempDir Path dir) {
        MatrixChannelConfig config = new MatrixChannelConfig(dir.resolve("matrix.json"));
        Spec spec = config.read();

        assertFalse(spec.enabled(), "an absent config file disables the channel");
        assertTrue(spec.homeserver().isEmpty());
        assertTrue(spec.accessToken().isEmpty());
        assertTrue(spec.userId().isEmpty());
        assertTrue(spec.allowedUserIds().isEmpty());
    }

    @Test
    void readsAnExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("matrix.json");
        Files.writeString(file, """
                { "homeserver": "https://m.example.org", "accessToken": "syt_live",
                  "allowedUserIds": ["@me:m.example.org"] }""");

        Spec spec = new MatrixChannelConfig(file).read();

        assertEquals(Optional.of("https://m.example.org"), spec.homeserver());
        assertEquals(Optional.of("syt_live"), spec.accessToken());
        assertEquals(Set.of("@me:m.example.org"), spec.allowedUserIds());
    }

    @Test
    void resolvesTheConfiguredHomeOverTheUserHomeDefault(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath().normalize(),
                MatrixChannelConfig.resolveHome(Optional.of(dir.toString()), "/home/other"));
        assertEquals(Path.of("/home/other", ".forvum").toAbsolutePath().normalize(),
                MatrixChannelConfig.resolveHome(Optional.empty(), "/home/other"));
        assertEquals(Path.of("/home/other", ".forvum").toAbsolutePath().normalize(),
                MatrixChannelConfig.resolveHome(Optional.of("   "), "/home/other"),
                "a blank configured home falls back to <user.home>/.forvum");
    }

    @Test
    void theInjectConstructorBindsChannelsMatrixJsonUnderTheConfiguredHome(@TempDir Path home)
            throws Exception {
        Files.createDirectories(home.resolve("channels"));
        Files.writeString(home.resolve("channels").resolve("matrix.json"),
                "{ \"accessToken\": \"from-home\" }");

        Spec spec = new MatrixChannelConfig(Optional.of(home.toString())).read();

        assertEquals(Optional.of("from-home"), spec.accessToken());
    }
}
