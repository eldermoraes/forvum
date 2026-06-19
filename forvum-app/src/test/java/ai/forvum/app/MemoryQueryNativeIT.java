package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;

/**
 * Native parity for {@code forvum memory query} (P3-2, #50) against a <em>seeded</em> SQLite database,
 * mirroring {@code SessionReplayNativeIT}'s two-launch dance: a first launch boots the binary and migrates
 * the schema, the test then inserts {@code semantic_memory} rows (some carrying a precomputed little-endian
 * float32 embedding BLOB) straight into that DB via plain JDBC, and a second launch runs a read-only SELECT
 * over them. This exercises the produced binary's SQLite/JDBC + BLOB stack natively — a native-only
 * persistence regression would surface here and nowhere else in the default native leg.
 *
 * <p>Deterministic and offline (no live embedding model), so it carries NO {@code @Tag("live")} and runs in
 * the default native leg (like {@code DoctorNativeIT}). The reindex/search path that needs a live embedding
 * model is covered separately by the {@code @Tag("live")} native turn IT.
 */
@QuarkusMainIntegrationTest
@TestProfile(MemoryQueryNativeIT.SeededHomeProfile.class)
class MemoryQueryNativeIT {

    @Test
    void nativeQueryReadsBackSeededSemanticMemory(QuarkusMainLauncher launcher) throws Exception {
        // 1. First launch migrates the schema on boot (an empty SELECT still exits 0).
        LaunchResult bootstrap = launcher.launch("memory", "query", "SELECT 1");
        assertEquals(0, bootstrap.exitCode(),
                () -> "the bootstrap SELECT must exit 0 natively; stderr: " + bootstrap.getErrorOutput());

        // 2. Seed semantic_memory rows (one carries a precomputed embedding BLOB) into the now-migrated DB.
        seed(SeededHomeProfile.HOME);

        // 3. A read-only SELECT reads them back.
        LaunchResult query = launcher.launch("memory", "query",
                "SELECT key, value FROM semantic_memory ORDER BY key");
        assertEquals(0, query.exitCode(),
                () -> "the seeded SELECT must exit 0 natively; stderr: " + query.getErrorOutput()
                        + "; stdout: " + query.getOutput());
        String out = query.getOutput();
        assertTrue(out.contains("native-color") && out.contains("teal"),
                () -> "native query must reproduce a seeded row; got: " + out);

        // A non-SELECT is refused by the read-only guard (exit 1) — the security gate works natively too.
        LaunchResult write = launcher.launch("memory", "query", "DELETE FROM semantic_memory");
        assertEquals(1, write.exitCode(),
                () -> "a non-SELECT must exit 1 natively; stdout: " + write.getOutput());
    }

    private static void seed(Path home) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + home.resolve("state").resolve("forvum.sqlite")
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(url)) {
            insertFact(connection, "native-color", "teal", encode(new float[] {1f, 0f, 0f, 0f}));
            insertFact(connection, "native-city", "Porto", null);
        }
    }

    private static void insertFact(Connection connection, String key, String value, byte[] embedding)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_memory(identity_id, agent_id, key, value, embedding, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            long now = 1000L;
            statement.setString(1, "default");
            statement.setString(2, "main");
            statement.setString(3, key);
            statement.setString(4, value);
            if (embedding == null) {
                statement.setNull(5, java.sql.Types.BLOB);
            } else {
                statement.setBytes(5, embedding);
            }
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    /** Little-endian float32 BLOB (same encoding as the engine VectorCodec), to prove the BLOB round-trips. */
    private static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** Points {@code forvum.home} at a throwaway temp dir; routes logs to stderr so stdout is just the table. */
    public static class SeededHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-native-memquery-home");
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
