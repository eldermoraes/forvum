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

    /**
     * The model that answered the turn, in the {@code ModelRef} canonical {@code provider:model} form
     * (#195). NULL on rows written with the judge disabled or unavailable — only a genuinely judged
     * verdict is attributed to a model, so {@code ModelHealthReader} never tallies a placeholder row.
     */
    @Column(name = "model")
    public String model;

    /** The normalized {@code [0,1]} verdict score (#195). NULL when the judge was disabled/unavailable. */
    @Column(name = "score")
    public Double score;

    @Column(name = "rationale")
    public String rationale;

    /**
     * Marked {@code true} (1) by session compaction when this verdict's {@link #turnId} references an
     * assistant message that was compacted out of the live window (P2-COMPACT). The row is NEVER
     * deleted — CAPR history is append-only and must not regress — only archived so live CAPR
     * aggregation can exclude it (ULTRAPLAN section 7.2 item 20).
     */
    @Column(name = "is_archived", nullable = false)
    public boolean archived;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
