package ai.forvum.engine.memoryquery;

import java.util.List;

/**
 * The result of a {@code forvum memory query '<SQL>'} run (P3-2, #50): the selected column names and the
 * rows (each a list of stringified cell values, {@code null} preserved as {@code null}). Built from a JDBC
 * {@code ResultSet} and printed by the CLI; never JSON-serialized, so it carries NO {@code @RegisterForReflection}
 * (mirrors {@code ReplaySession}/{@code DoctorReport}).
 *
 * @param columns the result-set column labels, in order
 * @param rows    the rows, each aligned to {@code columns}; capped by the caller's row limit
 * @param truncated {@code true} when the result was cut at the row limit (more rows existed)
 */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {

    public QueryResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
