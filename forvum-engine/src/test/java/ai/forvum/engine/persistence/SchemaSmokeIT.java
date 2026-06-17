package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The M5 Verify (ULTRAPLAN section 7.1): boots Quarkus against a fresh temp SQLite file, asserts
 * Flyway migrated V1 and every table exists, and inserts exactly one row per table. Runs via plain
 * Surefire — {@code forvum-engine} is a headless library, so the Dev MCP runner cannot attach
 * (CLAUDE.md section 4).
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class SchemaSmokeIT {

    private static final List<String> EXPECTED_TABLES = List.of(
            "sessions", "messages", "episodic_memory", "semantic_memory",
            "tool_invocations", "provider_calls", "capr_events", "tasks", "tool_approvals");

    private static final List<String> EXPECTED_INDEXES = List.of(
            "idx_sessions_identity", "idx_sessions_lastseen", "idx_messages_session", "idx_messages_agent",
            "idx_episodic_agent_session", "idx_semantic_agent", "idx_tool_session", "idx_tool_agent",
            "idx_provider_session", "idx_provider_agent", "idx_provider_fallback", "idx_capr_agent",
            "idx_tasks_agent", "idx_tasks_status", "idx_approvals_status", "idx_approvals_session");

    @Inject
    EntityManager em;

    @Test
    void flywayMigratedToHeadAndAllTablesExist() {
        // V1 baseline + V2__tasks.sql (P2-TASKLEDGER, the 'tasks' table) + V3__compaction.sql (P2-COMPACT,
        // the compaction columns) + V4__approvals.sql (P2-14 #39, the 'tool_approvals' queue), so the head
        // version is now 4.
        Object version = em.createNativeQuery(
                "select version from flyway_schema_history where success = 1 "
              + "order by installed_rank desc limit 1").getSingleResult();
        assertEquals("4", String.valueOf(version), "Flyway must have migrated to the head version (V4)");

        @SuppressWarnings("unchecked")
        List<String> tables = em.createNativeQuery(
                "select name from sqlite_master where type = 'table' and name not like 'sqlite_%'")
                .getResultList();
        assertTrue(tables.containsAll(EXPECTED_TABLES),
                "expected all migrated tables, found: " + tables);
    }

    @Test
    @Transactional
    void insertsOneRowPerTable() {
        // Self-contained: clear all tables first so the assertion is order-independent.
        MessageEntity.deleteAll();
        SessionEntity.deleteAll();
        EpisodicMemoryEntity.deleteAll();
        SemanticMemoryEntity.deleteAll();
        ToolInvocationEntity.deleteAll();
        ProviderCallEntity.deleteAll();
        CaprEventEntity.deleteAll();

        long now = System.currentTimeMillis();

        SessionEntity session = new SessionEntity();
        session.id = "sess-1";
        session.identityId = "identity-1";
        session.channelId = "tui";
        session.agentId = "main";
        session.startedAt = now;
        session.lastSeenAt = now;
        session.persist();
        // Flush the session before the FK-bearing message so the messages -> sessions FK holds
        // (MessageEntity has an IDENTITY id and would otherwise insert before the session).
        em.flush();

        MessageEntity message = new MessageEntity();
        message.sessionId = "sess-1";
        message.agentId = "main";
        message.role = "user";
        message.content = "hello";
        message.tokens = 3;
        message.blockType = "turn_message";
        message.createdAt = now;
        message.persist();

        EpisodicMemoryEntity episodic = new EpisodicMemoryEntity();
        episodic.agentId = "main";
        episodic.sessionId = "sess-1";
        episodic.eventType = "observation";
        episodic.content = "saw something";
        episodic.createdAt = now;
        episodic.persist();

        SemanticMemoryEntity semantic = new SemanticMemoryEntity();
        semantic.agentId = "main";
        semantic.key = "fav-color";
        semantic.value = "blue";
        semantic.createdAt = now;
        semantic.updatedAt = now;
        semantic.persist();

        ToolInvocationEntity tool = new ToolInvocationEntity();
        tool.sessionId = "sess-1";
        tool.agentId = "main";
        tool.toolName = "fs.read";
        tool.arguments = "{\"path\":\"/tmp\"}";
        tool.status = "ok";
        tool.latencyMs = 12;
        tool.createdAt = now;
        tool.persist();

        ProviderCallEntity providerCall = new ProviderCallEntity();
        providerCall.sessionId = "sess-1";
        providerCall.agentId = "main";
        providerCall.provider = "ollama";
        providerCall.model = "qwen3:1.7b";
        providerCall.tokensIn = 10;
        providerCall.tokensOut = 20;
        providerCall.latencyMs = 42;
        providerCall.isFallback = 0;
        providerCall.createdAt = now;
        providerCall.persist();

        CaprEventEntity capr = new CaprEventEntity();
        capr.sessionId = "sess-1";
        capr.agentId = "main";
        capr.turnId = 1L;
        capr.passed = 1;
        capr.judgeModel = "judge";
        capr.createdAt = now;
        capr.persist();

        em.flush();

        assertEquals(1, SessionEntity.count());
        assertEquals(1, MessageEntity.count());
        assertEquals(1, EpisodicMemoryEntity.count());
        assertEquals(1, SemanticMemoryEntity.count());
        assertEquals(1, ToolInvocationEntity.count());
        assertEquals(1, ProviderCallEntity.count());
        assertEquals(1, CaprEventEntity.count());
    }

    @Test
    void allMigratedIndexesExist() {
        @SuppressWarnings("unchecked")
        List<String> indexes = em.createNativeQuery(
                "select name from sqlite_master where type = 'index' and name not like 'sqlite_%'")
                .getResultList();
        assertTrue(indexes.containsAll(EXPECTED_INDEXES), "expected all migrated indexes, found: " + indexes);
    }

    @Test
    @Transactional
    void deletingASessionCascadesToItsMessages() {
        MessageEntity.deleteAll();
        SessionEntity.deleteAll();
        long now = System.currentTimeMillis();

        SessionEntity session = new SessionEntity();
        session.id = "cascade-s";
        session.identityId = "i";
        session.channelId = "tui";
        session.agentId = "main";
        session.startedAt = now;
        session.lastSeenAt = now;
        session.persist();
        em.flush();

        MessageEntity message = new MessageEntity();
        message.sessionId = "cascade-s";
        message.agentId = "main";
        message.role = "user";
        message.content = "hi";
        message.blockType = "turn_message";
        message.createdAt = now;
        message.persist();
        em.flush();
        assertEquals(1, MessageEntity.count());

        // foreign_keys=on (JDBC URL) + ON DELETE CASCADE must remove the child messages.
        SessionEntity.deleteById("cascade-s");
        em.flush();

        Number remaining = (Number) em.createNativeQuery(
                "select count(*) from messages where session_id = 'cascade-s'").getSingleResult();
        assertEquals(0L, remaining.longValue(), "messages must cascade-delete with their session");
    }

    @Test
    void duplicateSemanticKeyViolatesTheUniqueConstraint() {
        long now = System.currentTimeMillis();
        // Separate transactions: the duplicate INSERT executes at persist() time (IDENTITY id) and marks
        // its own tx rollback-only, so it must not poison a surrounding test transaction.
        QuarkusTransaction.requiringNew().run(() -> {
            SemanticMemoryEntity.deleteAll();
            persistSemantic(now);
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> persistSemantic(now)));
        assertTrue(mentionsUniqueViolation(ex),
                "expected a UNIQUE(agent_id, key) violation, got: " + ex);
    }

    private static boolean mentionsUniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().toUpperCase().contains("UNIQUE")) {
                return true;
            }
        }
        return false;
    }

    private static void persistSemantic(long now) {
        SemanticMemoryEntity e = new SemanticMemoryEntity();
        e.agentId = "dup-agent";
        e.key = "dup-key";
        e.value = "v";
        e.createdAt = now;
        e.updatedAt = now;
        e.persist();
    }
}
