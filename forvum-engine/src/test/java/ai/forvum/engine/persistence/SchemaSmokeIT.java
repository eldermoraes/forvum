package ai.forvum.engine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "tool_invocations", "provider_calls", "capr_events");

    @Inject
    EntityManager em;

    @Test
    void flywayMigratedV1AndAllTablesExist() {
        Object version = em.createNativeQuery(
                "select version from flyway_schema_history where success = 1 "
              + "order by installed_rank desc limit 1").getSingleResult();
        assertEquals("1", String.valueOf(version), "Flyway V1 must have applied successfully");

        @SuppressWarnings("unchecked")
        List<String> tables = em.createNativeQuery(
                "select name from sqlite_master where type = 'table' and name not like 'sqlite_%'")
                .getResultList();
        assertTrue(tables.containsAll(EXPECTED_TABLES),
                "expected all V1 tables, found: " + tables);
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
}
