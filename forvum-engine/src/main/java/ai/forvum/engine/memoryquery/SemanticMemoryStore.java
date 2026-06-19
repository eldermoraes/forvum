package ai.forvum.engine.memoryquery;

import ai.forvum.engine.persistence.SemanticMemoryEntity;

import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The persistence seam for {@link MemoryQueryService} (P3-2, #50). It is a SEPARATE bean (not inlined into
 * the service) so the write method is invoked across the CDI proxy and its transaction interceptor actually
 * fires — a self-invocation ({@code this.method(...)}) inside one bean bypasses the proxy and the
 * interceptor never runs, which would silently drop the reindex write (the [P2-15] self-invocation trap).
 *
 * <p><strong>The search-side reads go through raw JDBC, not Hibernate, on purpose.</strong> A reindex
 * commits the embedding BLOBs in a {@link QuarkusTransaction#requiringNew()} transaction (its own
 * EntityManager); a subsequent Hibernate/Panache read on the request's ambient EntityManager can return
 * those rows from its STALE first-level cache (still {@code embedding == null}) — observed live as the search
 * finding zero embedded rows right after a successful reindex. Reading the rows via the Agroal connection
 * sidesteps the L1 cache entirely (the same JDBC path {@link MemoryQueryService#query} uses), so search
 * always sees the committed BLOBs. The write still goes through Panache (a managed-entity UPDATE in a fresh
 * transaction).
 */
@ApplicationScoped
public class SemanticMemoryStore {

    /** A {@code semantic_memory} row that still needs an embedding, as primitives (no detached-entity reuse). */
    public record RowToEmbed(long id, String value) {
    }

    /** A computed embedding BLOB ready to apply to its row. */
    public record EmbeddedRow(long id, byte[] blob) {
    }

    /** A stored, embedded {@code semantic_memory} row read for the linear search. */
    public record EmbeddedFact(String identityId, String agentId, String key, String value, byte[] embedding) {
    }

    @Inject
    AgroalDataSource dataSource;

    /**
     * Read (via raw JDBC, L1-cache-immune) every embedded {@code semantic_memory} row for an identity/agent.
     * Returns the fact fields plus the raw embedding BLOB; only rows whose {@code embedding} is non-null and
     * non-empty are returned.
     */
    public List<EmbeddedFact> embeddedFacts(String identityId, String agentId) {
        String sql = "SELECT identity_id, agent_id, key, value, embedding FROM semantic_memory "
                + "WHERE identity_id = ? AND agent_id = ? AND embedding IS NOT NULL";
        List<EmbeddedFact> facts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identityId);
            statement.setString(2, agentId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byte[] embedding = rs.getBytes(5);
                    if (embedding == null || embedding.length == 0) {
                        continue;
                    }
                    facts.add(new EmbeddedFact(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), embedding));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading embedded memory failed: " + e.getMessage(), e);
        }
        return facts;
    }

    /**
     * Plan up to {@code batch} rows still missing an embedding, as {@code (id, value)} pairs. Read via JDBC
     * (L1-cache-immune) so a re-run after a prior reindex sees the committed BLOBs and does not re-plan
     * already-embedded rows.
     */
    public List<RowToEmbed> rowsNeedingEmbedding(String identityId, String agentId, int batch) {
        String sql = "SELECT id, value FROM semantic_memory "
                + "WHERE identity_id = ? AND agent_id = ? AND embedding IS NULL ORDER BY id LIMIT ?";
        List<RowToEmbed> planned = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identityId);
            statement.setString(2, agentId);
            statement.setInt(3, batch);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    planned.add(new RowToEmbed(rs.getLong(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Planning the reindex batch failed: " + e.getMessage(), e);
        }
        return planned;
    }

    /**
     * Apply pre-computed embedding BLOBs in one short write transaction; returns the count actually written.
     * Uses an explicit {@link QuarkusTransaction#requiringNew()} (committed when the lambda returns) rather
     * than {@code @Transactional}: a CLI command drives this on a thread that may already hold an ambient
     * request context (the {@code @QuarkusTest} thread, the picocli thread), and a fresh transaction
     * guarantees the BLOB UPDATEs commit independently of that ambient context.
     */
    @ActivateRequestContext
    public int applyEmbeddings(List<EmbeddedRow> embeddedRows) {
        int[] applied = {0};
        QuarkusTransaction.requiringNew().run(() -> {
            for (EmbeddedRow embeddedRow : embeddedRows) {
                SemanticMemoryEntity row = SemanticMemoryEntity.findById(embeddedRow.id());
                if (row == null || row.embedding != null) {
                    continue;
                }
                row.embedding = embeddedRow.blob();
                applied[0]++;
            }
        });
        return applied[0];
    }
}
