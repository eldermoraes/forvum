package ai.forvum.engine.memoryquery;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.persistence.SemanticMemoryEntity;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

import io.agroal.api.AgroalDataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Queryable semantic memory over the SQLite store (P3-2, #50, Risk #2). Three operations behind the
 * {@code forvum memory} CLI:
 * <ul>
 *   <li>{@link #query(String, int)} — run a read-only {@code SELECT} (guarded by {@link SqlGuard}, on a
 *       {@code read-only} JDBC connection) and return the rows;</li>
 *   <li>{@link #search(ModelRef, String, String, String, int)} — embed the query text and return the
 *       nearest {@code semantic_memory} rows by a pure-Java linear cosine scan over the stored vectors;</li>
 *   <li>{@link #reindex(ModelRef, String, String)} — populate the {@code embedding} BLOB for rows that
 *       lack one (new writes do not auto-embed — see the class note), so {@code search} can rank them.</li>
 * </ul>
 *
 * <p><strong>Why linear, not {@code vec0} (Risk #2):</strong> see {@link VectorMath}. The scan is fast
 * enough at 10k–100k rows and adds ZERO native surface.
 *
 * <p><strong>Write-time embedding is deliberately NOT automatic.</strong> Embedding every semantic-memory
 * write would put a blocking model call on the turn's critical path and require an embedding model to be
 * configured for any agent that writes facts. Instead population is an explicit, operator-driven
 * {@code forvum memory reindex} pass — cheap, native-safe, and decoupled from the turn (CLAUDE.md §14
 * "wire a write-time embedding hook ONLY if cheap and native-safe — otherwise provide an explicit
 * reindex path").
 */
@ApplicationScoped
public class MemoryQueryService {

    /** How many rows we re-embed per write transaction, to keep each commit short on the single SQLite writer. */
    private static final int REINDEX_BATCH = 50;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    EmbeddingSelector embeddings;

    /**
     * Run a guarded read-only {@code SELECT} and return at most {@code limit} rows. The SQL is validated by
     * {@link SqlGuard#requireReadOnlySelect} and the connection is opened {@code read-only}, so a write or
     * schema change is refused at two layers. Cell values are stringified ({@code null} preserved).
     *
     * @throws IllegalArgumentException if the SQL is not a single read-only SELECT
     * @throws java.io.UncheckedIOException never; SQL errors surface as {@link IllegalStateException}
     */
    public QueryResult query(String sql, int limit) {
        String safeSql = SqlGuard.requireReadOnlySelect(sql);
        if (limit <= 0) {
            throw new IllegalArgumentException("Row limit must be positive, got " + limit + ".");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(safeSql)) {
                // Fetch one beyond the limit so we can report truncation honestly.
                statement.setMaxRows(limit + 1);
                try (ResultSet rs = statement.executeQuery()) {
                    return readResult(rs, limit);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + e.getMessage(), e);
        }
    }

    private static QueryResult readResult(ResultSet rs, int limit) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() == limit) {
                truncated = true; // there is at least one more row (we fetched limit+1)
                break;
            }
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.add(value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated);
    }

    /**
     * Embed {@code queryText} with the {@code embeddingRef} model and return the {@code topK} nearest
     * {@code semantic_memory} rows (most similar first) for the given identity/agent, by a linear cosine
     * scan over the stored embedding BLOBs. Rows with no embedding (never reindexed) are skipped — run
     * {@code reindex} first. {@code @ActivateRequestContext} so the Panache read has a request-scoped
     * {@code EntityManager} off the CLI thread (mirrors {@code SessionReplayer}); the read needs no
     * transaction.
     */
    @ActivateRequestContext
    public List<SearchHit> search(ModelRef embeddingRef, String queryText, String identityId,
            String agentId, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("top-K must be positive, got " + topK + ".");
        }
        EmbeddingModel model = embeddings.resolve(embeddingRef);
        float[] queryVector = embed(model, queryText);

        List<SemanticMemoryEntity> rows = SemanticMemoryEntity.list(
                "identityId = ?1 and agentId = ?2 and embedding is not null", identityId, agentId);

        List<SearchHit> hits = new ArrayList<>(rows.size());
        for (SemanticMemoryEntity row : rows) {
            float[] vector = VectorCodec.decode(row.embedding);
            if (vector == null || vector.length != queryVector.length) {
                // Skip a row embedded with a different model (dimension mismatch) rather than fail the search.
                continue;
            }
            double score = VectorMath.cosine(queryVector, vector);
            hits.add(new SearchHit(row.identityId, row.agentId, row.key, row.value, score));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    /**
     * Populate the {@code embedding} BLOB for every {@code semantic_memory} row of the given identity/agent
     * that lacks one, using the {@code embeddingRef} model. Returns the number of rows embedded.
     *
     * <p>Each batch is: a short no-tx READ planning the batch (capture {@code (id, value)} primitives so the
     * detached entities are never reused), the blocking model calls with NO DB connection held (CLAUDE.md
     * §14 [M7]), then a short WRITE transaction applying the BLOBs. The loop drains until no row needs an
     * embedding; a batch that applies fewer rows than it planned (a concurrent delete/embed) stops the loop
     * so it cannot spin.
     */
    public int reindex(ModelRef embeddingRef, String identityId, String agentId) {
        EmbeddingModel model = embeddings.resolve(embeddingRef);
        int total = 0;
        while (true) {
            List<RowToEmbed> batch = rowsNeedingEmbedding(identityId, agentId, REINDEX_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            // Embed OUTSIDE any transaction — no Agroal/SQLite connection is held across the model call.
            List<EmbeddedRow> embeddedRows = new ArrayList<>(batch.size());
            for (RowToEmbed row : batch) {
                embeddedRows.add(new EmbeddedRow(row.id(), VectorCodec.encode(embed(model, row.value()))));
            }
            int applied = applyEmbeddings(embeddedRows);
            total += applied;
            if (applied < batch.size()) {
                // Fewer applied than planned (a row was deleted/embedded concurrently) — avoid an infinite loop.
                break;
            }
        }
        return total;
    }

    /** A row that still needs an embedding, captured as primitives (no detached entity reuse). */
    record RowToEmbed(long id, String value) {
    }

    /** A computed BLOB ready to apply to its row. */
    private record EmbeddedRow(long id, byte[] blob) {
    }

    /** Read (no transaction) up to {@code batch} rows still missing an embedding, as {@code (id, value)} pairs. */
    @ActivateRequestContext
    List<RowToEmbed> rowsNeedingEmbedding(String identityId, String agentId, int batch) {
        List<SemanticMemoryEntity> rows = SemanticMemoryEntity.list(
                "identityId = ?1 and agentId = ?2 and embedding is null order by id", identityId, agentId);
        List<RowToEmbed> planned = new ArrayList<>(Math.min(batch, rows.size()));
        for (SemanticMemoryEntity row : rows) {
            if (planned.size() == batch) {
                break;
            }
            planned.add(new RowToEmbed(row.id, row.value));
        }
        return planned;
    }

    /** Apply pre-computed embedding BLOBs in one short write transaction; returns the count actually written. */
    @ActivateRequestContext
    @Transactional
    int applyEmbeddings(List<EmbeddedRow> embeddedRows) {
        int applied = 0;
        for (EmbeddedRow embeddedRow : embeddedRows) {
            SemanticMemoryEntity row = SemanticMemoryEntity.findById(embeddedRow.id());
            if (row == null || row.embedding != null) {
                continue;
            }
            row.embedding = embeddedRow.blob();
            applied++;
        }
        return applied;
    }

    private static float[] embed(EmbeddingModel model, String text) {
        Embedding embedding = model.embed(text).content();
        if (embedding == null) {
            throw new IllegalStateException("Embedding model returned no vector for the input text.");
        }
        return embedding.vector();
    }
}
