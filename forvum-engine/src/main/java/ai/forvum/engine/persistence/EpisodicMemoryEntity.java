package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code episodic_memory} table (per-agent, per-session event log). */
@Entity
@Table(name = "episodic_memory")
public class EpisodicMemoryEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "content", nullable = false)
    public String content;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
