package ai.forvum.engine.agent;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.persistence.SessionEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Ensures the {@code sessions} row a turn's {@code messages} reference (FK target) exists. A session is
 * created on first use and its {@code last_seen_at} bumped on reuse. The channels (M15-M17) drive turns
 * through {@link TurnService}, which calls {@link #ensureSession(String, AgentId, String, String)} with
 * the resolved identity + channel; the no-channel internal path (engine tests / future internal callers)
 * uses the {@link #ensureSession(String, AgentId)} overload with placeholder identity/channel.
 */
@ApplicationScoped
public class SessionManager {

    static final String DEFAULT_IDENTITY = "default";
    static final String DEFAULT_CHANNEL = "internal";

    /** No-channel internal path: a placeholder {@code default}/{@code internal} identity/channel. */
    @Transactional
    public void ensureSession(String sessionId, AgentId agentId) {
        ensureSession(sessionId, agentId, DEFAULT_IDENTITY, DEFAULT_CHANNEL);
    }

    /** Channel-driven path: persist the resolved {@code identityId} + {@code channelId} on first use. */
    @Transactional
    public void ensureSession(String sessionId, AgentId agentId, String identityId, String channelId) {
        SessionEntity existing = SessionEntity.findById(sessionId);
        long now = System.currentTimeMillis();
        if (existing == null) {
            SessionEntity session = new SessionEntity();
            session.id = sessionId;
            session.identityId = identityId;
            session.channelId = channelId;
            session.agentId = agentId.value();
            session.startedAt = now;
            session.lastSeenAt = now;
            session.persist();
        } else {
            existing.lastSeenAt = now;
        }
    }
}
