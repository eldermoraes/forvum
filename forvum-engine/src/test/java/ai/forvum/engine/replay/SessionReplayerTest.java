package ai.forvum.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.SessionManager;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.PersistenceTestHomeProfile;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link SessionReplayer} reads a stored session back as an ordered, in-process view (P2-8): the
 * {@code messages} of the session interleaved with its {@code tool_invocations}, for the
 * {@code forvum replay} debugging command. Real SQLite via the seeded temp home (CLAUDE.md section 4).
 *
 * <p>The ordering is turn-logical, not raw wall-clock: the engine ledgers a tool call mid-turn (an
 * earlier {@code created_at}) but commits the turn's user+assistant pair atomically at turn-end (M7
 * persist-after-success), so a tool's {@code created_at} precedes its own turn's messages. The replayer
 * therefore surfaces each tool between the user message and the assistant reply of the turn that produced
 * it (flush tools up to each assistant's {@code created_at}), which is what a human reading the transcript
 * expects.
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class SessionReplayerTest {

    @Inject
    SessionReplayer replayer;

    @Inject
    SessionManager sessions;

    @Test
    @Transactional
    void replaysMessagesAndToolInvocationsInTurnOrder() {
        AgentId main = new AgentId("main");
        String sessionId = "replay-order";
        sessions.ensureSession(sessionId, main);

        // Turn 1 (no tools): user then assistant, both committed at turn end.
        seedMessage(sessionId, "user", "hi", 100);
        seedMessage(sessionId, "assistant", "hello", 101);
        // Turn 2 (one tool): the tool is ledgered mid-turn (earlier ts=200) while the user+assistant pair
        // is committed at turn end (ts=210/211) — exactly the production timing this replayer reorders.
        seedTool(sessionId, "web.fetch", "{\"url\":\"x\"}", "<html/>", "ok", 12, 200);
        seedMessage(sessionId, "user", "fetch x", 210);
        seedMessage(sessionId, "assistant", "done", 211);

        ReplaySession session = replayer.replay(sessionId);

        assertTrue(session.found(), "the seeded session must be found");
        assertEquals(sessionId, session.sessionId());
        assertEquals("main", session.agentId());

        List<ReplaySegment> segs = session.segments();
        assertEquals(5, segs.size(), "4 messages + 1 tool invocation");
        assertMessage(segs.get(0), "user", "hi");
        assertMessage(segs.get(1), "assistant", "hello");
        assertMessage(segs.get(2), "user", "fetch x");
        ToolSegment tool = assertInstanceOf(ToolSegment.class, segs.get(3),
                "the tool surfaces between its turn's user message and the assistant reply");
        assertEquals("web.fetch", tool.toolName());
        assertEquals("ok", tool.status());
        assertEquals("<html/>", tool.result());
        assertMessage(segs.get(4), "assistant", "done");
    }

    @Test
    void reportsNotFoundForAnUnknownSession() {
        ReplaySession session = replayer.replay("no-such-session");
        assertFalse(session.found(), "an unknown session id is reported as not found");
        assertTrue(session.segments().isEmpty(), "a not-found session has no segments");
    }

    @Test
    @Transactional
    void replaysAnEmptySessionAsFoundWithNoSegments() {
        sessions.ensureSession("replay-empty", new AgentId("main"));
        ReplaySession session = replayer.replay("replay-empty");
        assertTrue(session.found(), "a session row with no messages is still found");
        assertTrue(session.segments().isEmpty(), "no messages or tools means no segments");
    }

    @Test
    @Transactional
    void replaysMultipleToolsOfOneTurnInCallOrderBetweenUserAndAssistant() {
        AgentId main = new AgentId("main");
        String sessionId = "replay-multitool";
        sessions.ensureSession(sessionId, main);

        // One turn with two tool calls ledgered mid-turn (ts 300, 301), the user/assistant pair committed at
        // turn end (ts 310/311). Exercises the inner flush loop running more than one iteration.
        seedTool(sessionId, "fs.read", "{\"path\":\"a\"}", "alpha", "ok", 5, 300);
        seedTool(sessionId, "fs.read", "{\"path\":\"b\"}", "beta", "ok", 6, 301);
        seedMessage(sessionId, "user", "read both", 310);
        seedMessage(sessionId, "assistant", "done", 311);

        List<ReplaySegment> segs = replayer.replay(sessionId).segments();

        assertEquals(4, segs.size(), "user + 2 tools + assistant");
        assertMessage(segs.get(0), "user", "read both");
        ToolSegment first = assertInstanceOf(ToolSegment.class, segs.get(1), "first tool, in call order");
        assertEquals("alpha", first.result());
        ToolSegment second = assertInstanceOf(ToolSegment.class, segs.get(2), "second tool, in call order");
        assertEquals("beta", second.result());
        assertMessage(segs.get(3), "assistant", "done");
    }

    @Test
    @Transactional
    void replaysToolsOfAnIncompleteTurnAfterTheLastMessage() {
        AgentId main = new AgentId("main");
        String sessionId = "replay-incomplete";
        sessions.ensureSession(sessionId, main);

        // A completed turn, then a turn that ledgered a tool but failed before persisting its assistant reply
        // (M7 persist-after-success): the tool's created_at (500) is past the last assistant (401), so it has
        // no assistant to flush before — it must trail at the end. Exercises the trailing drain loop.
        seedMessage(sessionId, "user", "first", 400);
        seedMessage(sessionId, "assistant", "ok", 401);
        seedTool(sessionId, "web.fetch", "{\"url\":\"y\"}", "<body/>", "error", 9, 500);

        List<ReplaySegment> segs = replayer.replay(sessionId).segments();

        assertEquals(3, segs.size(), "user + assistant + the trailing tool of the failed turn");
        assertMessage(segs.get(0), "user", "first");
        assertMessage(segs.get(1), "assistant", "ok");
        ToolSegment trailing = assertInstanceOf(ToolSegment.class, segs.get(2),
                "a tool with no following assistant trails at the end");
        assertEquals("web.fetch", trailing.toolName());
        assertEquals("error", trailing.status());
    }

    private static void assertMessage(ReplaySegment segment, String role, String content) {
        MessageSegment message = assertInstanceOf(MessageSegment.class, segment,
                "expected a message segment for role '" + role + "'");
        assertEquals(role, message.role());
        assertEquals(content, message.content());
    }

    private static void seedMessage(String sessionId, String role, String content, long createdAt) {
        MessageEntity message = new MessageEntity();
        message.sessionId = sessionId;
        message.agentId = "main";
        message.role = role;
        message.content = content;
        message.tokens = null;
        message.createdAt = createdAt;
        message.persist();
    }

    private static void seedTool(String sessionId, String toolName, String arguments, String result,
            String status, int latencyMs, long createdAt) {
        ToolInvocationEntity tool = new ToolInvocationEntity();
        tool.sessionId = sessionId;
        tool.agentId = "main";
        tool.toolName = toolName;
        tool.arguments = arguments;
        tool.result = result;
        tool.status = status;
        tool.latencyMs = latencyMs;
        tool.createdAt = createdAt;
        tool.persist();
    }
}
