package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps the {@code provider_calls} table — the LLM-call ledger the budget meter and fallback path
 * read and write. {@code isFallback} is 1 when this call only happened because an earlier chain
 * entry failed (consumed by M8).
 */
@Entity
@Table(name = "provider_calls")
public class ProviderCallEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "session_id", nullable = false)
    public String sessionId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "provider", nullable = false)
    public String provider;

    @Column(name = "model", nullable = false)
    public String model;

    @Column(name = "tokens_in", nullable = false)
    public long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    public long tokensOut;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "latency_ms", nullable = false)
    public long latencyMs;

    @Column(name = "is_fallback", nullable = false)
    public int isFallback;

    @Column(name = "error")
    public String error;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}
