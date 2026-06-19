package ai.forvum.engine.memoryquery;

import java.util.Locale;
import java.util.Set;

/**
 * Read-only guard for the {@code forvum memory query '<SQL>'} command (P3-2, #50). A user-supplied SQL
 * string is run against the operational SQLite store, so it MUST be confined to a single {@code SELECT}
 * (or read-only CTE) — never a write, schema change, attach, or {@code PRAGMA}.
 *
 * <p>Defense is layered: this guard rejects anything that is not a single read-only statement, AND the
 * caller opens the JDBC connection {@code read_only=true} (SQLite refuses any write at the engine level),
 * AND a row cap is applied. The guard alone is conservative — it rejects rather than tries to be clever —
 * so it is the primary gate; the connection flag and cap are belt-and-braces.
 *
 * <p>Pure and stateless: a property-style unit test covers the accept/reject matrix with no CDI or DB.
 */
public final class SqlGuard {

    /**
     * Statement-leading keywords that mutate state or escape the read-only contract. A query whose first
     * token is one of these is rejected outright; the set is also scanned for anywhere in the (comment- and
     * string-stripped) statement to catch a stacked or smuggled write.
     */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "ATTACH", "DETACH", "PRAGMA", "VACUUM", "REINDEX", "ANALYZE", "BEGIN", "COMMIT",
            "ROLLBACK", "SAVEPOINT", "RELEASE", "GRANT", "REVOKE", "MERGE");

    private SqlGuard() {
    }

    /**
     * Validate that {@code sql} is a single read-only statement and return its trimmed form (with any
     * trailing {@code ;} removed), ready to execute. Throws {@link IllegalArgumentException} with an
     * actionable message otherwise.
     */
    public static String requireReadOnlySelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL query is empty.");
        }
        String stripped = stripCommentsAndStrings(sql).trim();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("SQL query has no executable statement (only comments).");
        }

        // Reject stacked statements: a ';' that is not the final character means more than one statement.
        String withoutTrailingSemicolon = stripped.endsWith(";")
                ? stripped.substring(0, stripped.length() - 1)
                : stripped;
        if (withoutTrailingSemicolon.contains(";")) {
            throw new IllegalArgumentException(
                    "Only a single SELECT statement is allowed (no ';'-separated statements).");
        }

        String upper = withoutTrailingSemicolon.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            throw new IllegalArgumentException(
                    "Only read-only queries are allowed: the statement must start with SELECT (or a WITH "
                            + "... SELECT). Got: " + firstWord(withoutTrailingSemicolon));
        }

        for (String token : upper.split("[^A-Z]+")) {
            if (FORBIDDEN_KEYWORDS.contains(token)) {
                throw new IllegalArgumentException(
                        "Only read-only queries are allowed: the statement contains the forbidden keyword '"
                                + token + "'.");
            }
        }

        // Return the original SQL (preserving its string literals/comments for the engine), minus a trailing
        // ';' — we validated the comment/string-stripped form, but execute the user's text as written.
        String trimmed = sql.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
    }

    /** First whitespace-delimited word, for a friendlier rejection message. */
    private static String firstWord(String sql) {
        String[] parts = sql.trim().split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0];
    }

    /**
     * Replace string/identifier literals and SQL comments with spaces so the keyword scan cannot be fooled
     * by a write keyword hidden inside a quoted literal or a comment. Handles {@code '...'} and
     * {@code "..."} literals (with doubled-quote escapes), {@code --} line comments, and {@code /* ... *}{@code /}
     * block comments. Conservative by design: an unterminated literal/comment swallows the rest of the
     * input, which only makes the guard stricter.
     */
    static String stripCommentsAndStrings(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == quote) {
                        // A doubled quote is an escaped quote inside the literal, not the end.
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++; // consume the closing quote
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                // line comment: skip to end of line
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // block comment: skip to the closing */
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
