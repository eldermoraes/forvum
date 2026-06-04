package ai.forvum.engine.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps the {@code sessions} table (TEXT primary key, supplied by the channel). */
@Entity
@Table(name = "sessions")
public class SessionEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public String id;

    @Column(name = "identity_id", nullable = false)
    public String identityId;

    @Column(name = "channel_id", nullable = false)
    public String channelId;

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(name = "started_at", nullable = false)
    public long startedAt;

    @Column(name = "last_seen_at", nullable = false)
    public long lastSeenAt;

    @Column(name = "metadata_json")
    public String metadataJson;
}
