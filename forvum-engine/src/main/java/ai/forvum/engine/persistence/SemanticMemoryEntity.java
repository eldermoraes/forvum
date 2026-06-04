package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code semantic_memory} table (embedded long-term facts; unique per (agent_id, key)). */
@Entity
@Table(name = "semantic_memory")
public class SemanticMemoryEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "key", nullable = false)
    public String key;

    @Column(name = "value", nullable = false)
    public String value;

    /** Raw float32 vector stored as a BLOB; null until an embedding model populates it. */
    @Column(name = "embedding")
    public byte[] embedding;

    @Column(name = "source")
    public String source;

    @Column(name = "created_at", nullable = false)
    public long createdAt;

    @Column(name = "updated_at", nullable = false)
    public long updatedAt;
}
