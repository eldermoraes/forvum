package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;

/**
 * Native parity for {@code forvum memory forget} (#175), the new delete surface — the two-launch dance of
 * {@code MemoryQueryNativeIT}: a first launch migrates the schema, the test seeds {@code semantic_memory}
 * rows via plain JDBC, then later launches forget a single key and then {@code --all}, reading back with
 * {@code memory query} to prove the delete took. Exercises the produced binary's SQLite delete path
 * natively; deterministic and offline (no embedding model), so it runs in the default native leg with no
 * {@code @Tag("live")}.
 */
@QuarkusMainIntegrationTest
@TestProfile(MemoryForgetNativeIT.SeededHomeProfile.class)
class MemoryForgetNativeIT {

    @Test
    void nativeForgetDeletesAKeyThenAll(QuarkusMainLauncher launcher) throws Exception {
        // 1. First launch migrates the schema on boot.
        assertEquals(0, launcher.launch("memory", "query", "SELECT 1").exitCode(),
                "the bootstrap SELECT must migrate + exit 0 natively");

        // 2. Seed two facts straight into the migrated DB.
        seed(SeededHomeProfile.HOME);

        // 3. Forget one key.
        LaunchResult forgetOne = launcher.launch("memory", "forget", "native-color");
        assertEquals(0, forgetOne.exitCode(),
                () -> "forget <key> must exit 0 natively; stderr: " + forgetOne.getErrorOutput());
        assertTrue(forgetOne.getOutput().contains("Forgot fact 'native-color'"),
                () -> "forget must report the removal; got: " + forgetOne.getOutput());

        // 4. The forgotten key is gone; the other fact remains.
        LaunchResult afterOne = launcher.launch("memory", "query",
                "SELECT key FROM semantic_memory ORDER BY key");
        assertFalse(afterOne.getOutput().contains("native-color"), "the forgotten key is gone natively");
        assertTrue(afterOne.getOutput().contains("native-city"), "the other fact is untouched");

        // 5. Forget the rest with --all, then confirm the store is empty.
        LaunchResult forgetAll = launcher.launch("memory", "forget", "--all");
        assertEquals(0, forgetAll.exitCode(),
                () -> "forget --all must exit 0 natively; stderr: " + forgetAll.getErrorOutput());
        assertTrue(forgetAll.getOutput().contains("Forgot 1 fact(s)"),
                () -> "forget --all reports the count; got: " + forgetAll.getOutput());

        LaunchResult empty = launcher.launch("memory", "query", "SELECT key FROM semantic_memory");
        assertFalse(empty.getOutput().contains("native-city"), "every fact is now forgotten natively");
    }

    private static void seed(Path home) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + home.resolve("state").resolve("forvum.sqlite")
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(url)) {
            insertFact(connection, "native-color", "teal");
            insertFact(connection, "native-city", "Porto");
        }
    }

    private static void insertFact(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_memory(identity_id, agent_id, key, value, embedding, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, NULL, ?, ?)")) {
            long now = 1000L;
            statement.setString(1, "default");
            statement.setString(2, "main");
            statement.setString(3, key);
            statement.setString(4, value);
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    /** Points {@code forvum.home} at a throwaway temp dir; routes logs to stderr so stdout is just the command output. */
    public static class SeededHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-native-memforget-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.log.console.stderr", "true");
        }
    }
}
