package ai.forvum.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link FileApiKeyStore} — the P2-10 file-backed provider-key store. */
class FileApiKeyStoreTest {

    @Test
    void storeThenReadRoundTrips(@TempDir Path home) {
        FileApiKeyStore.store(home, "anthropic", "sk-secret-123");
        assertEquals(Optional.of("sk-secret-123"), FileApiKeyStore.read(home, "anthropic"));
    }

    @Test
    void storeWritesUnderStateCredentials(@TempDir Path home) {
        FileApiKeyStore.store(home, "openai", "key");
        Path expected = home.resolve("state").resolve("credentials").resolve("openai");
        assertTrue(Files.isRegularFile(expected), "key file should live under state/credentials/<id>");
        assertEquals(expected, FileApiKeyStore.credentialFile(home, "openai"));
    }

    @Test
    void readMissingFileIsEmpty(@TempDir Path home) {
        assertEquals(Optional.empty(), FileApiKeyStore.read(home, "google"));
    }

    @Test
    void readBlankFileIsEmpty(@TempDir Path home) throws IOException {
        Path file = FileApiKeyStore.credentialFile(home, "anthropic");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "   \n\t  ");
        assertEquals(Optional.empty(), FileApiKeyStore.read(home, "anthropic"),
                "a whitespace-only credential file reads as no key");
    }

    @Test
    void readStripsSurroundingWhitespace(@TempDir Path home) throws IOException {
        Path file = FileApiKeyStore.credentialFile(home, "openai");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "  sk-trimmed\n");
        assertEquals(Optional.of("sk-trimmed"), FileApiKeyStore.read(home, "openai"));
    }

    @Test
    void readOfADirectoryPathIsEmpty(@TempDir Path home) throws IOException {
        // The credential "file" path is a directory => not a regular file => empty, no crash.
        Path file = FileApiKeyStore.credentialFile(home, "google");
        Files.createDirectories(file);
        assertEquals(Optional.empty(), FileApiKeyStore.read(home, "google"));
    }

    @Test
    void storeOverwritesExistingKey(@TempDir Path home) {
        FileApiKeyStore.store(home, "anthropic", "old-key");
        FileApiKeyStore.store(home, "anthropic", "new-key");
        assertEquals(Optional.of("new-key"), FileApiKeyStore.read(home, "anthropic"));
    }

    @Test
    void storeStripsTheKeyOnWrite(@TempDir Path home) {
        FileApiKeyStore.store(home, "openai", "  padded-key  ");
        assertEquals(Optional.of("padded-key"), FileApiKeyStore.read(home, "openai"));
    }

    @Test
    void storeRejectsNullOrBlankKey(@TempDir Path home) {
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.store(home, "anthropic", null));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.store(home, "anthropic", "   "));
    }

    @Test
    void credentialFileRejectsTraversalIds(@TempDir Path home) {
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, "../evil"));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, "a/b"));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, ".."));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, "."));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, ""));
        assertThrows(IllegalArgumentException.class, () -> FileApiKeyStore.credentialFile(home, null));
    }

    @Test
    void credentialFileAcceptsExtensionIdShapedNames(@TempDir Path home) {
        // dots/dashes/underscores are valid (e.g. a hypothetical "azure-openai" provider id).
        Path file = FileApiKeyStore.credentialFile(home, "azure-openai_v2.1");
        assertEquals(home.resolve("state").resolve("credentials").resolve("azure-openai_v2.1"), file);
    }

    @Test
    void resolveHomeUsesConfiguredValueWhenPresent(@TempDir Path dir) {
        Path resolved = FileApiKeyStore.resolveHome(Optional.of(dir.toString()));
        assertEquals(dir.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveHomeFallsBackToUserHomeWhenAbsentOrBlank() {
        Path expected = Path.of(System.getProperty("user.home")).resolve(".forvum").toAbsolutePath().normalize();
        assertEquals(expected, FileApiKeyStore.resolveHome(Optional.empty()));
        assertEquals(expected, FileApiKeyStore.resolveHome(Optional.of("   ")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void storeMakesTheFileOwnerOnly(@TempDir Path home) throws IOException {
        FileApiKeyStore.store(home, "anthropic", "sk-secret");
        Path file = FileApiKeyStore.credentialFile(home, "anthropic");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "the credential file must be 0600");
        Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(file.getParent());
        assertFalse(dirPerms.contains(PosixFilePermission.OTHERS_READ),
                "the credentials directory must not be world-readable");
    }
}
