package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

import org.junit.jupiter.api.Tag;
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
 * Risk #5 (P3-2, #50): the real-embedding-provider native smoke. Drives the produced native binary
 * OUT-OF-PROCESS through {@code forvum memory reindex}/{@code search} against a live Ollama embedding model
 * ({@code ollama:all-minilm}), proving the binary can actually embed and rank — the embedding analogue of
 * {@code OllamaNativeTurnIT}'s chat turn (the deterministic {@code MemoryQueryNativeIT} covers only the
 * query path, with no embedding model in the image).
 *
 * <p>Two-launch JDBC seed dance (like {@code SessionReplayNativeIT}): a first launch migrates the schema,
 * the test inserts {@code semantic_memory} rows via JDBC, then {@code reindex} embeds them through the live
 * model and {@code search} ranks the matching fact first. A native-only embedding HTTP/JSON/reflection
 * regression would surface here and nowhere else.
 *
 * <p>{@code @Tag("live")} — off by default; the CI {@code native-turn} job runs it with
 * {@code -DitGroups=live -DitExcludedGroups=none} (it pulls {@code all-minilm} alongside the chat model).
 */
@QuarkusMainIntegrationTest
@TestProfile(MemorySearchNativeIT.LiveHomeProfile.class)
@Tag("live")
class MemorySearchNativeIT {

    private static final String EMBED_MODEL = "ollama:all-minilm";

    @Test
    void nativeReindexThenSearchAgainstRealOllamaRanksTheMatchingFact(QuarkusMainLauncher launcher)
            throws Exception {
        // 1. First launch migrates the schema on boot.
        LaunchResult bootstrap = launcher.launch("memory", "query", "SELECT 1");
        assertEquals(0, bootstrap.exitCode(),
                () -> "the bootstrap SELECT must exit 0 natively; stderr: " + bootstrap.getErrorOutput());

        // 2. Seed two semantic-memory facts (no embeddings yet) into the now-migrated DB.
        seed(LiveHomeProfile.HOME);

        // 3. Reindex embeds them through the live Ollama embedding model.
        LaunchResult reindex = launcher.launch("memory", "reindex", "--model", EMBED_MODEL);
        assertEquals(0, reindex.exitCode(),
                () -> "native reindex against real Ollama must exit 0; stderr: " + reindex.getErrorOutput()
                        + "; stdout: " + reindex.getOutput());
        assertTrue(reindex.getOutput().contains("Embedded 2"),
                () -> "reindex must report embedding both rows; got: " + reindex.getOutput());

        // 4. Search ranks the fact whose value matches the query first (real embedding similarity).
        LaunchResult search = launcher.launch("memory", "search", "the colour of the sky", "--model",
                EMBED_MODEL, "--top-k", "2");
        assertEquals(0, search.exitCode(),
                () -> "native search against real Ollama must exit 0; stderr: " + search.getErrorOutput()
                        + "; stdout: " + search.getOutput());
        assertTrue(search.getOutput().contains("blue"),
                () -> "search must surface the colour fact for a sky-colour query; got: " + search.getOutput());
    }

    private static void seed(Path home) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + home.resolve("state").resolve("forvum.sqlite")
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(url)) {
            insertFact(connection, "sky-colour", "blue");
            insertFact(connection, "favourite-food", "pizza");
        }
    }

    private static void insertFact(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_memory(identity_id, agent_id, key, value, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
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

    /** Points {@code forvum.home} at a temp dir; logs to stderr so stdout is the command's output alone. */
    public static class LiveHomeProfile implements QuarkusTestProfile {

        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-native-memsearch-home");
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
