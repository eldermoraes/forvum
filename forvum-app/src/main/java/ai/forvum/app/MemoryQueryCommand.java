package ai.forvum.app;

import ai.forvum.engine.memoryquery.MemoryQueryService;
import ai.forvum.engine.memoryquery.QueryResult;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code forvum memory query '<SQL>'} (P3-2, #50): run a read-only {@code SELECT} over the SQLite store and
 * print the rows as a simple table. The SQL is validated to be a single read-only statement and is run on a
 * {@code read-only} connection (two layers), so a write/DDL/PRAGMA is refused. Exit 0 on success; 1 on an
 * invalid query (not a SELECT, or a SQL error). Results print to stdout; the boot logs go to stderr
 * (%prod), so the output is just the table.
 */
@CommandLine.Command(
        name = "query",
        description = "Run a read-only SELECT over the SQLite store and print the rows.")
public class MemoryQueryCommand implements Callable<Integer> {

    /** Default cap so a forgotten {@code LIMIT} never floods the terminal; raise with {@code --limit}. */
    private static final int DEFAULT_LIMIT = 100;

    @Inject
    MemoryQueryService service;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<sql>",
            description = "A single read-only SELECT (e.g. 'SELECT key, value FROM semantic_memory').")
    String sql;

    @CommandLine.Option(
            names = "--limit",
            paramLabel = "<n>",
            description = "Maximum rows to print (default ${DEFAULT-VALUE}).")
    int limit = DEFAULT_LIMIT;

    @Override
    public Integer call() {
        QueryResult result;
        try {
            result = service.query(sql, limit);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid query: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Query failed: " + e.getMessage());
            return 1;
        }
        print(result);
        return 0;
    }

    private static void print(QueryResult result) {
        System.out.println(String.join(" | ", result.columns()));
        for (List<String> row : result.rows()) {
            System.out.println(row.stream()
                    .map(cell -> cell == null ? "(null)" : cell)
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(""));
        }
        System.out.println("(" + result.rows().size() + " row(s)"
                + (result.truncated() ? ", truncated at the limit — raise --limit to see more" : "") + ")");
    }
}
