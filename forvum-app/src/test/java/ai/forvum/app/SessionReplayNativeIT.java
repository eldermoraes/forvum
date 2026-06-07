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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;

/**
 * Native parity for {@code forvum replay} (P2-8): exercises the produced binary OUT-OF-PROCESS against a
 * <em>seeded</em> SQLite database. Unlike {@code DoctorNativeIT} (config files only), replay reads the
 * operational DB, so the binary must have run Flyway/Panache and the SQLite/JDBC stack natively — a native-only
 * persistence regression would surface here and nowhere else in the default native leg.
 *
 * <p>Deterministic and offline (no live LLM), so it carries NO {@code @Tag("live")} and runs in the default
 * native leg — a free, real native exercise of the DB-reading path. The seeding cannot use a prior {@code ask}
 * turn (that would need a live provider in the image), so it is done in three steps within the test:
 * <ol>
 *   <li>a first {@code replay} launch boots the binary, which migrates the schema (the session is absent →
 *       exit 1), creating {@code <home>/state/forvum.sqlite};</li>
 *   <li>the test inserts a {@code sessions} row plus a user/assistant message pair straight into that
 *       now-migrated DB via plain JDBC (no Flyway history to forge — the binary already wrote it);</li>
 *   <li>a second {@code replay} launch reads the seeded rows back and prints them.</li>
 * </ol>
 * All three share one {@code forvum.home} (the profile's temp dir, propagated to the out-of-process binary as
 * {@code -Dforvum.home} via {@link SeededHomeProfile#getConfigOverrides()}), hence one database file.
 */
@QuarkusMainIntegrationTest
@TestProfile(SessionReplayNativeIT.SeededHomeProfile.class)
class SessionReplayNativeIT {

    private static final String SESSION = "cli:nativeit";
    private static final String USER_TEXT = "native-seeded user question";
    private static final String ASSISTANT_TEXT = "native-seeded assistant reply";

    @Test
    void nativeReplayReproducesASeededSession(QuarkusMainLauncher launcher) throws Exception {
        // 1. First launch migrates the schema on boot; the session does not exist yet → not-found (exit 1).
        LaunchResult bootstrap = launcher.launch("replay", SESSION);
        assertEquals(1, bootstrap.exitCode(),
                () -> "an unseeded session must report not-found (exit 1) natively; stderr: "
                        + bootstrap.getErrorOutput());

        // 2. Seed a session + a user/assistant pair directly into the now-migrated SQLite.
        seed(SeededHomeProfile.HOME);

        // 3. Replaying the seeded session reproduces both messages.
        LaunchResult replay = launcher.launch("replay", SESSION);
        assertEquals(0, replay.exitCode(),
                () -> "replay of a seeded session must exit 0 natively; stderr: " + replay.getErrorOutput()
                        + "; stdout: " + replay.getOutput());
        String out = replay.getOutput();
        assertTrue(out.contains(USER_TEXT),
                () -> "native replay must reproduce the seeded user message; got: " + out);
        assertTrue(out.contains(ASSISTANT_TEXT),
                () -> "native replay must reproduce the seeded assistant reply; got: " + out);
    }

    private static void seed(Path home) throws Exception {
        // xerial registers via the JDBC4 ServiceLoader; the explicit load is a belt-and-braces no-op.
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + home.resolve("state").resolve("forvum.sqlite")
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (PreparedStatement session = connection.prepareStatement(
                    "INSERT INTO sessions(id, identity_id, channel_id, agent_id, started_at, last_seen_at, "
                            + "metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                session.setString(1, SESSION);
                session.setString(2, "default");
                session.setString(3, "cli");
                session.setString(4, "main");
                session.setLong(5, 1000L);
                session.setLong(6, 1000L);
                session.setString(7, null);
                session.executeUpdate();
            }
            insertMessage(connection, "user", USER_TEXT, 1001L);
            insertMessage(connection, "assistant", ASSISTANT_TEXT, 1002L);
        }
    }

    private static void insertMessage(Connection connection, String role, String content, long createdAt)
            throws Exception {
        try (PreparedStatement message = connection.prepareStatement(
                "INSERT INTO messages(session_id, agent_id, role, content, tokens, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            message.setString(1, SESSION);
            message.setString(2, "main");
            message.setString(3, role);
            message.setString(4, content);
            message.setNull(5, java.sql.Types.INTEGER);
            message.setLong(6, createdAt);
            message.executeUpdate();
        }
    }

    /** Points {@code forvum.home} at a throwaway temp dir and routes logs to stderr (clean stdout for replay). */
    public static class SeededHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-native-replay-home");
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
