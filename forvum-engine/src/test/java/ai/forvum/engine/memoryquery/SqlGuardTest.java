package ai.forvum.engine.memoryquery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Accept/reject matrix for {@link SqlGuard} (P3-2, #50). The read-only gate is security-critical. */
class SqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM semantic_memory",
            "select key, value from semantic_memory where agent_id = 'main'",
            "  SELECT 1  ",
            "SELECT * FROM messages LIMIT 10;",
            "WITH recent AS (SELECT * FROM messages ORDER BY id DESC LIMIT 5) SELECT * FROM recent",
            "SELECT value FROM semantic_memory WHERE value = 'do not DELETE me'", // write keyword in a literal
            "SELECT count(*) FROM provider_calls -- DROP TABLE provider_calls",   // write keyword in a comment
    })
    void acceptsReadOnlySelects(String sql) {
        String safe = SqlGuard.requireReadOnlySelect(sql);
        // Trailing ';' stripped, leading/trailing whitespace trimmed.
        org.junit.jupiter.api.Assertions.assertTrue(
                safe.toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")
                        || safe.toUpperCase(java.util.Locale.ROOT).startsWith("WITH"),
                "guarded SQL must remain a SELECT/WITH; got: " + safe);
        org.junit.jupiter.api.Assertions.assertFalse(safe.endsWith(";"), "trailing ';' must be stripped");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO semantic_memory(key, value) VALUES ('x', 'y')",
            "UPDATE semantic_memory SET value = 'z'",
            "DELETE FROM semantic_memory",
            "DROP TABLE semantic_memory",
            "ALTER TABLE semantic_memory ADD COLUMN x TEXT",
            "CREATE TABLE evil (x INT)",
            "PRAGMA writable_schema = 1",
            "ATTACH DATABASE 'evil.db' AS evil",
            "VACUUM",
            "REINDEX semantic_memory",
            "REPLACE INTO semantic_memory(key, value) VALUES ('a', 'b')",
            "BEGIN; DELETE FROM messages; COMMIT",
            "SELECT 1; DELETE FROM messages",               // stacked statement
            "SELECT 1; SELECT 2",                            // stacked SELECTs are still rejected
            "DELETE FROM messages -- SELECT looks fine",     // write hidden before a comment
    })
    void rejectsAnythingThatIsNotASingleReadOnlySelect(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.requireReadOnlySelect(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t ", "-- just a comment", "/* block only */"})
    void rejectsEmptyOrCommentOnly(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.requireReadOnlySelect(sql));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.requireReadOnlySelect(null));
    }

    @Test
    void stripCommentsAndStringsNeutralizesLiteralsAndComments() {
        // The keyword scan must not see DELETE/DROP inside literals or comments.
        String stripped = SqlGuard.stripCommentsAndStrings(
                "SELECT 'DELETE' /* DROP */ , \"INSERT\" -- UPDATE\nFROM t");
        String upper = stripped.toUpperCase(java.util.Locale.ROOT);
        org.junit.jupiter.api.Assertions.assertFalse(upper.contains("DELETE"), "literal DELETE must be stripped");
        org.junit.jupiter.api.Assertions.assertFalse(upper.contains("DROP"), "comment DROP must be stripped");
        org.junit.jupiter.api.Assertions.assertFalse(upper.contains("INSERT"), "literal INSERT must be stripped");
        org.junit.jupiter.api.Assertions.assertFalse(upper.contains("UPDATE"), "line-comment UPDATE must be stripped");
        org.junit.jupiter.api.Assertions.assertTrue(upper.contains("SELECT") && upper.contains("FROM"));
    }

    @Test
    void preservesTheOriginalLiteralTextInReturnedSql() {
        // A legit literal containing a forbidden word must survive into the executed SQL verbatim.
        String sql = "SELECT value FROM semantic_memory WHERE value = 'please DROP by later'";
        assertEquals(sql, SqlGuard.requireReadOnlySelect(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM update_log",        // identifier CONTAINS 'update' — must NOT be rejected
            "SELECT delete_flag FROM messages", // column contains 'delete'
            "SELECT * FROM create_audit",       // table contains 'create'
    })
    void acceptsIdentifiersThatMerelyContainAForbiddenKeyword(String sql) {
        // Identifier-aware tokenization: a forbidden keyword embedded in an identifier (split by '_') is fine;
        // only a standalone keyword is rejected.
        org.junit.jupiter.api.Assertions.assertEquals(sql, SqlGuard.requireReadOnlySelect(sql));
    }

    @Test
    void stillRejectsAStandaloneForbiddenKeyword() {
        // The standalone keyword (delimited by whitespace) is still caught.
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.requireReadOnlySelect("SELECT 1 UNION SELECT 1; DELETE FROM messages"));
    }
}
