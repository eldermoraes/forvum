package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code forvum memory query/search} end-to-end through the assembled CLI (P3-2, #50). The launches share
 * one {@code forvum.home} (one SQLite DB): an {@code ask} turn seeds {@code messages} rows the read-only
 * {@code query} then reads back. Like {@code ask}/{@code replay}, {@code memory} is NOT a {@code CommandMode}
 * one-shot — it boots the full Flyway/Panache path. The reindex-then-search ranking is covered
 * deterministically by the engine {@code MemoryQueryServiceIT} (and the native IT seeds embeddings via
 * JDBC); here we cover the CLI surface: the read-only guard, a real SELECT, graceful empty search, and a bad
 * model ref. The profile unbinds the bundled Web channel's HTTP listener so the several sequential launches
 * do not contend for the port (the [P2-13] multi-launch lesson).
 */
@QuarkusMainTest
@TestProfile(MemoryCommandTest.MemoryHomeProfile.class)
class MemoryCommandTest {

    /** Seeds the {@code fake}-backed main agent and unbinds HTTP for the sequential launches ([P2-13]). */
    public static class MemoryHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-memcmd-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "quarkus.http.host-enabled", "false");
        }
    }

    @Test
    void queryReadsBackRowsAPriorAskTurnWrote(QuarkusMainLauncher launcher) {
        LaunchResult ask = launcher.launch("ask", "remember this question");
        assertEquals(0, ask.exitCode(), () -> "ask must seed messages; stderr: " + ask.getErrorOutput());

        LaunchResult query = launcher.launch("memory", "query",
                "SELECT role, content FROM messages ORDER BY id", "--limit", "10");
        assertEquals(0, query.exitCode(),
                () -> "a read-only SELECT must exit 0; stderr: " + query.getErrorOutput()
                        + "; stdout: " + query.getOutput());
        String out = query.getOutput();
        assertTrue(out.contains("role | content"), () -> "query must print the column header; got: " + out);
        assertTrue(out.contains("remember this question"),
                () -> "query must reproduce the seeded user message; got: " + out);
        assertTrue(out.contains("row(s)"), () -> "query must print the row count; got: " + out);
    }

    @Test
    void queryRejectsANonSelectAndExitsNonZero(QuarkusMainLauncher launcher) {
        LaunchResult query = launcher.launch("memory", "query", "DELETE FROM messages");
        assertEquals(1, query.exitCode(),
                () -> "a non-SELECT must exit 1; stdout: " + query.getOutput());
        assertTrue(query.getErrorOutput().toLowerCase().contains("read-only"),
                () -> "the rejection must mention read-only; got: " + query.getErrorOutput());
    }

    @Test
    void searchWithNoEmbeddedMemoryExitsZeroWithAHint(QuarkusMainLauncher launcher) {
        // Nothing has been reindexed, so search finds no embedded rows — a clean exit 0 with the reindex hint,
        // NOT a failure. Uses the fake embedding model so no live Ollama is needed.
        LaunchResult search = launcher.launch("memory", "search", "anything", "--model", "fake:embed");
        assertEquals(0, search.exitCode(),
                () -> "search over empty memory must exit 0; stderr: " + search.getErrorOutput()
                        + "; stdout: " + search.getOutput());
        assertTrue(search.getOutput().contains("reindex"),
                () -> "search must point the operator at reindex; got: " + search.getOutput());
    }

    @Test
    void searchWithAnInvalidModelRefExitsNonZero(QuarkusMainLauncher launcher) {
        LaunchResult search = launcher.launch("memory", "search", "anything", "--model", "no-colon-here");
        assertEquals(1, search.exitCode(),
                () -> "an unparseable --model must exit 1; stdout: " + search.getOutput());
        assertTrue(search.getErrorOutput().contains("Invalid embedding model ref"),
                () -> "the error must name the bad --model value; got: " + search.getErrorOutput());
    }

    @Test
    void reindexWithAnInvalidModelRefExitsNonZero(QuarkusMainLauncher launcher) {
        LaunchResult reindex = launcher.launch("memory", "reindex", "--model", "no-colon-here");
        assertEquals(1, reindex.exitCode(),
                () -> "an unparseable --model must exit 1; stdout: " + reindex.getOutput());
        assertTrue(reindex.getErrorOutput().contains("Invalid embedding model ref"),
                () -> "the error must name the bad --model value; got: " + reindex.getErrorOutput());
    }

    @Test
    void reindexEmbedsNothingWhenNoFactsExistForTheAgent(QuarkusMainLauncher launcher) {
        // Reindex with the fake embedding model: with no semantic_memory rows seeded, it embeds zero rows and
        // exits 0 — the empty-store branch, reachable without a live model (the fake provider supplies one).
        LaunchResult reindex = launcher.launch("memory", "reindex", "--model", "fake:embed", "--agent",
                "no-such-agent");
        assertEquals(0, reindex.exitCode(),
                () -> "reindex over an empty agent must exit 0; stderr: " + reindex.getErrorOutput()
                        + "; stdout: " + reindex.getOutput());
        assertTrue(reindex.getOutput().contains("Embedded 0"),
                () -> "reindex must report zero rows embedded; got: " + reindex.getOutput());
    }
}
