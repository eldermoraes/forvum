package ai.forvum.engine.replay;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Role;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.agent.SessionManager;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.graph.ReplayContext;
import ai.forvum.engine.graph.ReplayToolSource;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;
import ai.forvum.engine.persistence.ToolInvocationEntity;
import ai.forvum.engine.routing.LlmSelector;
import ai.forvum.sdk.ApprovalContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Re-runs a stored session under a substituted model, the Phase-3 extension of P2-8's read-only replay
 * (P3-9, #57). It reads the original session's user messages and recorded tool outputs, then re-drives
 * each user turn through {@link Agent#respond} into a NEW session using the substituted model — so the
 * rerun produces a comparable, independently replayable trace without mutating the original. The
 * substitution is recorded in the new session's {@code metadata_json} (no schema change).
 *
 * <p>Determinism (the locked #57 decisions): recorded tool outputs are served FIFO-per-tool from a
 * {@link ReplayToolSource} (a miss yields a synthetic marker) and SHORT-CIRCUIT every non-deterministic
 * step — the graph runs in replay mode ({@link ReplayContext#CURRENT_REPLAY} bound), so it never
 * re-executes a real tool, never re-retrieves memory, and disables proxy-model compression. Memory-policy
 * substitution is descoped; a rerun is always a new session, never an in-place re-execution.
 *
 * <p>Limitation: a model-emitted {@code spawn_worker} during a rerun re-executes the worker (workers are
 * not captured in {@code tool_invocations}); recorded tool outputs cover the belt-tool ReAct loop, which
 * is the substitution surface #57 targets.
 */
@ApplicationScoped
public class SessionSubstitutionReplayer {

    private static final Logger LOG = Logger.getLogger(SessionSubstitutionReplayer.class);

    @Inject
    AgentRegistry registry;

    @Inject
    Agent agent;

    @Inject
    LlmSelector llmSelector;

    @Inject
    SessionManager sessions;

    @Inject
    ObjectMapper mapper;

    /**
     * Replay {@code originalSessionId} under {@code substituteModel}. {@code @ActivateRequestContext} so the
     * Panache reads + the driven turns have a request-scoped {@code EntityManager} when a CLI command drives
     * this off the main thread (mirrors {@code SessionReplayer}/{@code TurnService}).
     */
    @ActivateRequestContext
    public SubstitutionResult replay(String originalSessionId, ModelRef substituteModel) {
        SessionEntity original = SessionEntity.findById(originalSessionId);
        if (original == null) {
            return SubstitutionResult.notFound(originalSessionId);
        }
        AgentId agentId = new AgentId(original.agentId);

        List<String> userTexts = MessageEntity
                .<MessageEntity>list("sessionId = ?1 and role = ?2 order by id",
                        originalSessionId, Role.USER.dbValue())
                .stream().map(message -> message.content).toList();

        List<ReplayToolSource.RecordedTool> recorded = ToolInvocationEntity
                .<ToolInvocationEntity>list("sessionId = ?1 order by createdAt, id", originalSessionId)
                .stream().map(tool -> new ReplayToolSource.RecordedTool(tool.toolName, tool.result)).toList();

        String newSessionId = originalSessionId + ":replay:" + UUID.randomUUID().toString().substring(0, 8);
        sessions.ensureSession(newSessionId, agentId, original.identityId, original.channelId);
        recordSubstitution(newSessionId, originalSessionId, substituteModel);

        // One FIFO source shared across all turns: the Nth call to a tool across the whole rerun consumes
        // the Nth recorded result for that tool (turn-spanning FIFO-per-tool).
        ReplayToolSource toolSource = new ReplayToolSource(recorded);
        ChatModel model;
        try {
            // Resolving an UNKNOWN substitute provider throws here (before any turn) — keep it inside the
            // safety net so replay() never propagates an uncaught exception to the CLI.
            model = llmSelector.resolve(substituteModel, agentId.value(), newSessionId);
            registry.getOrCreate(agentId); // ensure the persona is registered for the @AgentScoped respond
        } catch (RuntimeException e) {
            String cause = deepestMessage(e);
            LOG.warnf("Replay of '%s' could not resolve substitute model %s: %s",
                    originalSessionId, substituteModel, cause);
            return SubstitutionResult.partial(originalSessionId, newSessionId, 0, substituteModel,
                    "could not resolve substitute model " + substituteModel + ": " + cause);
        }

        int completed = 0;
        for (String userText : userTexts) {
            try {
                ScopedValue.where(CurrentAgent.CURRENT_AGENT, agentId)
                        .where(CurrentAgent.CURRENT_TURN, UUID.randomUUID())
                        .where(ReplayContext.CURRENT_REPLAY, toolSource)
                        .where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE) // no human in a replay
                        .call(() -> agent.respond(newSessionId, userText, model));
                completed++;
            } catch (RuntimeException e) {
                // A rerun turn failed (e.g. a substituted provider that is unreachable/unconfigured — the
                // headline --model risk). Unlike a channel turn, there is no TurnService to convert this to
                // a terminal ErrorEvent, so surface it cleanly: stop, and report which turn failed + its
                // root cause. Turns 1..completed are already persisted in the new (partial) session.
                String cause = deepestMessage(e);
                LOG.warnf("Replay of '%s' stopped at turn %d: %s", originalSessionId, completed + 1, cause);
                return SubstitutionResult.partial(originalSessionId, newSessionId, completed, substituteModel,
                        "turn " + (completed + 1) + " failed: " + cause);
            }
        }
        return SubstitutionResult.replayed(originalSessionId, newSessionId, completed, substituteModel);
    }

    /** Walk to the deepest cause's message (hop-capped against a cause cycle), for an actionable diagnostic. */
    private static String deepestMessage(Throwable t) {
        Throwable cause = t;
        for (int i = 0; i < 20 && cause.getCause() != null && cause.getCause() != cause; i++) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** Record {@code {replayOf, substitution:{model}}} on the new session's metadata_json (best-effort). */
    private void recordSubstitution(String newSessionId, String originalSessionId, ModelRef substituteModel) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("replayOf", originalSessionId);
        metadata.set("substitution", mapper.createObjectNode().put("model", substituteModel.toString()));
        String json;
        try {
            json = mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Could not serialize replay metadata for session '%s'", newSessionId);
            return; // provenance is best-effort — never fail the replay over it
        }
        // A separate committed transaction (the ensureSession write already committed via SessionManager);
        // self-invoking a @Transactional method would not intercept (CDI proxy), so drive it explicitly.
        QuarkusTransaction.requiringNew().run(() -> {
            SessionEntity session = SessionEntity.findById(newSessionId);
            if (session != null) {
                session.metadataJson = json;
            }
        });
    }
}
