package ai.forvum.engine.agent;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.SessionEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Ensures the {@code sessions} row a turn's {@code messages} reference (FK target) exists. v0.1 is
 * minimal: real per-conversation session lifecycle (identity + channel) arrives with the channels
 * (M15-M17); here a session is created on first use with placeholder identity/channel and its
 * {@code last_seen_at} bumped on reuse.
 */
@ApplicationScoped
public class SessionManager {

    static final String DEFAULT_IDENTITY = "default";
    static final String DEFAULT_CHANNEL = "internal";

    @Transactional
    public void ensureSession(String sessionId, AgentId agentId) {
        SessionEntity existing = SessionEntity.findById(sessionId);
        long now = System.currentTimeMillis();
        if (existing == null) {
            SessionEntity session = new SessionEntity();
            session.id = sessionId;
            session.identityId = DEFAULT_IDENTITY;
            session.channelId = DEFAULT_CHANNEL;
            session.agentId = agentId.value();
            session.startedAt = now;
            session.lastSeenAt = now;
            session.persist();
        } else {
            existing.lastSeenAt = now;
        }
    }
}
