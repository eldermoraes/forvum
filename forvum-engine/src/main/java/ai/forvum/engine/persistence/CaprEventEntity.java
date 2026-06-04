package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the {@code capr_events} table (per-turn pass/fail verdict). In V1 {@code turnId} is an INTEGER
 * referencing {@code messages.id}; V2 migrates it to a shared UUID (out of M5 scope).
 */
@Entity
@Table(name = "capr_events")
public class CaprEventEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "turn_id", nullable = false)
    public long turnId;

    @Column(name = "passed", nullable = false)
    public int passed;

    @Column(name = "judge_model", nullable = false)
    public String judgeModel;

    @Column(name = "rationale")
    public String rationale;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
