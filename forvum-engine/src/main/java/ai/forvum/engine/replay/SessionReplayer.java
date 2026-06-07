package ai.forvum.engine.replay;

import ai.forvum.core.Role;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.engine.persistence.ToolInvocationEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a stored session back as an ordered, in-process view for the {@code forvum replay} command (P2-8):
 * the session's {@code messages} interleaved with its {@code tool_invocations}, oldest first. Read-only — it
 * reproduces the recorded sequence and never re-invokes the model (re-execution-with-substitution is the
 * Phase-3 extension P3-9). The Phase-2 scope is a deterministic, offline debugging view.
 */
@ApplicationScoped
public class SessionReplayer {

    /**
     * Replay {@code sessionId}: its header and the ordered segments, or a {@code found == false} result if
     * no {@code sessions} row matches. {@code @ActivateRequestContext} so the Panache reads have a
     * request-scoped {@code EntityManager} when a CLI command drives this off the main thread (mirrors
     * {@code TurnService}); the reads need no transaction.
     */
    @ActivateRequestContext
    public ReplaySession replay(String sessionId) {
        SessionEntity session = SessionEntity.findById(sessionId);
        if (session == null) {
            return ReplaySession.notFound(sessionId);
        }

        List<MessageEntity> messages = MessageEntity.list("sessionId = ?1 order by id", sessionId);
        List<ToolInvocationEntity> tools =
                ToolInvocationEntity.list("sessionId = ?1 order by createdAt, id", sessionId);

        return new ReplaySession(
                session.id, true, session.agentId, session.channelId, session.identityId,
                session.startedAt, interleave(messages, tools));
    }

    /**
     * Merge messages (in {@code id} order) with tool invocations (in {@code created_at} order), surfacing
     * each tool within the turn that produced it. The engine ledgers a tool call mid-turn but commits the
     * turn's user+assistant pair atomically at turn-end (M7 persist-after-success), so a tool's
     * {@code created_at} precedes its own turn's messages. Flushing the not-yet-emitted tools whose
     * {@code created_at <= } the current assistant message places each tool between the user message and the
     * assistant reply of its turn — the transcript order a human expects. Any tools after the last assistant
     * (e.g. a turn that failed before persisting its reply) trail at the end.
     */
    private static List<ReplaySegment> interleave(
            List<MessageEntity> messages, List<ToolInvocationEntity> tools) {
        List<ReplaySegment> segments = new ArrayList<>(messages.size() + tools.size());
        int next = 0;
        for (MessageEntity message : messages) {
            if (Role.fromDbValue(message.role) == Role.ASSISTANT) {
                while (next < tools.size() && tools.get(next).createdAt <= message.createdAt) {
                    segments.add(toSegment(tools.get(next++)));
                }
            }
            segments.add(new MessageSegment(message.role, message.content, message.createdAt));
        }
        while (next < tools.size()) {
            segments.add(toSegment(tools.get(next++)));
        }
        return segments;
    }

    private static ToolSegment toSegment(ToolInvocationEntity tool) {
        return new ToolSegment(
                tool.toolName, tool.arguments, tool.result, tool.status, tool.latencyMs, tool.createdAt);
    }
}
