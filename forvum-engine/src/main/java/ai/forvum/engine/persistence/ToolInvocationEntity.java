package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code tool_invocations} table (every tool call, inputs, outputs, outcome). */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    @Column(name = "arguments", nullable = false)
    public String arguments;

    @Column(name = "result")
    public String result;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "latency_ms")
    public Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
