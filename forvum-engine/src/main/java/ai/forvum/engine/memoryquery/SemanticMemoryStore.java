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
import java.sql.Types;
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
     * Upsert one long-term fact with its embedding, scoped to {@code (identityId, agentId, key)} — the
     * off-turn write path of #175. Uses SQLite's ATOMIC {@code INSERT ... ON CONFLICT(identity_id, agent_id,
     * key) DO UPDATE} so a re-write of the same key updates value/source/embedding/{@code updated_at}
     * (deterministic dedup) with NO read-modify-write race: two concurrent writes of the same new key on
     * the virtual-thread executor cannot both INSERT and violate the UNIQUE constraint (the losing writer's
     * statement resolves to the UPDATE branch instead of throwing). Unlike {@code AgentMemory.recordFact}
     * (which is {@code @AgentScoped} and resolves the tenant from ScopedValues), this takes identity/agent
     * explicitly so the async writer can call it on a plain virtual thread with no ScopedValue re-bind.
     * Wrapped in its own request context + fresh transaction (like {@link #applyEmbeddings}) so it commits
     * independently of any ambient turn transaction. The blocking embedding call must happen BEFORE this.
     */
    @ActivateRequestContext
    public void upsertFact(String identityId, String agentId, String key, String value, String source,
            byte[] embedding) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO semantic_memory (identity_id, agent_id, key, value, embedding, source, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(identity_id, agent_id, key) DO UPDATE SET value = excluded.value, "
                + "embedding = excluded.embedding, source = excluded.source, updated_at = excluded.updated_at";
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, identityId);
                statement.setString(2, agentId);
                statement.setString(3, key);
                statement.setString(4, value);
                if (embedding == null) {
                    statement.setNull(5, Types.BLOB);
                } else {
                    statement.setBytes(5, embedding);
                }
                statement.setString(6, source);
                statement.setLong(7, now);
                statement.setLong(8, now);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Upserting memory fact failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Delete the fact named {@code key} for {@code (identityId, agentId)}, returning how many rows were
     * removed (0 or 1). The delete is identity/agent-scoped — {@code forvum memory forget} (#175) can never
     * touch another identity's or agent's fact. Filters by key in-memory to avoid the SQL-reserved {@code
     * key} column in a JPQL where-clause (the {@link #upsertFact} idiom).
     */
    @ActivateRequestContext
    public int deleteFact(String identityId, String agentId, String key) {
        int[] deleted = {0};
        QuarkusTransaction.requiringNew().run(() -> SemanticMemoryEntity
                .<SemanticMemoryEntity>list("identityId = ?1 and agentId = ?2", identityId, agentId).stream()
                .filter(fact -> fact.key.equals(key))
                .forEach(fact -> {
                    fact.delete();
                    deleted[0]++;
                }));
        return deleted[0];
    }

    /**
     * Delete every long-term fact for {@code (identityId, agentId)} — {@code forvum memory forget --all}
     * (#175) — returning the number removed. Identity/agent-scoped: another identity's facts are untouched.
     */
    @ActivateRequestContext
    public long deleteAllFacts(String identityId, String agentId) {
        long[] deleted = {0};
        QuarkusTransaction.requiringNew().run(() -> deleted[0] =
                SemanticMemoryEntity.delete("identityId = ?1 and agentId = ?2", identityId, agentId));
        return deleted[0];
    }

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
