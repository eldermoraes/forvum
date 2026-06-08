package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code messages} table (append-only chat history; FK to {@code sessions}). */
@Entity
@Table(name = "messages")
public class MessageEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "role", nullable = false)
    public String role;

    @Column(name = "content", nullable = false)
    public String content;

    @Column(name = "tokens")
    public Integer tokens;

    /**
     * Structural block discriminator (P2-COMPACT) carrying a {@link ai.forvum.core.BlockType} db-literal.
     * Defaults to {@code turn_message} (every pre-P2-COMPACT row, via the V2 migration default) so the
     * normal conversational path is unchanged; {@code turn_reasoning}/{@code turn_artifact}/
     * {@code tool_execution} rows let session compaction strip orphaned reasoning/artifact blocks while
     * conservatively retaining tool-execution blocks still connected to a retained turn.
     */
    @Column(name = "block_type", nullable = false)
    public String blockType;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
