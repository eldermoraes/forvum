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

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
