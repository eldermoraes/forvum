package ai.forvum.engine.memoryquery;

import io.agroal.api.AgroalDataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the episodic tier for the local {@code MemoryProvider} (#175). Episodic rows carry no
 * {@code identity_id} of their own (the V1 schema keys them by {@code agent_id} + {@code session_id}), so
 * identity scoping is a JOIN to {@code sessions.identity_id} — an episode belongs to the identity that
 * owns its session. Reads go through raw JDBC (like {@link SemanticMemoryStore}) so they are immune to a
 * stale Hibernate L1 cache and never enlist the turn's transaction.
 */
@ApplicationScoped
public class EpisodicMemoryStore {

    /** One episodic row read for recall, as primitives (no detached-entity reuse). */
    public record RecentEpisode(long id, String content, long createdAt) {
    }

    @Inject
    AgroalDataSource dataSource;

    /**
     * The most recent episodes for {@code identityId}/{@code agentId} across every session that identity
     * owns, newest first, capped at {@code limit}. When {@code excludeSessionId} is non-blank its episodes
     * are omitted (the current session's events are already in the short-term {@code messages} window, so a
     * cross-session recall need not repeat them).
     */
    public List<RecentEpisode> recentEpisodes(String identityId, String agentId, String excludeSessionId,
            int limit) {
        boolean exclude = excludeSessionId != null && !excludeSessionId.isBlank();
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.content, e.created_at FROM episodic_memory e "
                        + "JOIN sessions s ON e.session_id = s.id "
                        + "WHERE s.identity_id = ? AND e.agent_id = ?");
        if (exclude) {
            sql.append(" AND e.session_id <> ?");
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT ?");

        List<RecentEpisode> episodes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, identityId);
            statement.setString(index++, agentId);
            if (exclude) {
                statement.setString(index++, excludeSessionId);
            }
            statement.setInt(index, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    episodes.add(new RecentEpisode(rs.getLong(1), rs.getString(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading episodic memory failed: " + e.getMessage(), e);
        }
        return episodes;
    }
}
