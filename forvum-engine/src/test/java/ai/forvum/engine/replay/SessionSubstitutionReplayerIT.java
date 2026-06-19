package ai.forvum.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Role;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.agent.AgentRegistryTestHomeProfile;
import ai.forvum.engine.agent.EchoModelProvider;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The P3-9 replay-with-substitution path (#57): a stored session re-runs under a substituted model into a
 * NEW session whose trace reflects the substitute, with the substitution recorded in metadata_json. Proven
 * with the {@code faker} persona model (replies "pong") substituted by the distinct {@code echo} model —
 * so a green test requires the substituted model actually drove the rerun ([M19]). Surefire-run.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class SessionSubstitutionReplayerIT {

    @Inject
    AgentRegistry registry;

    @Inject
    SessionSubstitutionReplayer replayer;

    @Test
    void returnsNotFoundForAnUnknownSession() {
        SubstitutionResult result = replayer.replay("cli:nobody-here", ModelRef.parse("echo:m"));
        assertFalse(result.found(), "replaying a non-existent session reports not found");
    }

    @Test
    void reRunsTheSessionUnderTheSubstitutedModelIntoANewReplayableSession() {
        AgentId faker = new AgentId("faker");
        Agent agent = registry.getOrCreate(faker);
        String original = "cli:replay-sub-it";

        // 1. Original turn: the faker persona model (fake:test-model) replies "pong".
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, faker)
                .call(() -> agent.respond(original, "the original question"));

        // 2. Replay the session with the echo model substituted.
        SubstitutionResult result = replayer.replay(original, ModelRef.parse("echo:m"));

        assertTrue(result.found());
        assertNotEquals(original, result.newSessionId(), "the rerun is a NEW session, never in-place");
        assertEquals(1, result.turnCount(), "the single original user turn was re-run");
        assertEquals(ModelRef.parse("echo:m"), result.substituteModel());

        // 3. The new session carries the SUBSTITUTE model's reply (echo), not the original "pong", and the
        //    substitution is recorded in metadata_json (zero schema change).
        QuarkusTransaction.requiringNew().run(() -> {
            List<MessageEntity> replyRows = MessageEntity.list(
                    "sessionId = ?1 and role = ?2 order by id", result.newSessionId(), Role.ASSISTANT.dbValue());
            assertEquals(1, replyRows.size(), "one assistant reply in the rerun");
            assertEquals(EchoModelProvider.REPLY, replyRows.get(0).content,
                    "the rerun used the SUBSTITUTED echo model, not the persona's fake model (which replies 'pong')");

            SessionEntity session = SessionEntity.findById(result.newSessionId());
            assertNotNull(session.metadataJson, "the substitution is recorded in the new session's metadata_json");
            assertTrue(session.metadataJson.contains("echo:m"), "metadata names the substituted model");
            assertTrue(session.metadataJson.contains(original), "metadata records the replayOf source session");
        });
    }

    @Test
    void aFailingRerunTurnIsReportedAsPartialNotPropagated() {
        AgentId faker = new AgentId("faker");
        Agent agent = registry.getOrCreate(faker);
        String original = "cli:replay-fail-it";

        // 1. A successful original turn (faker persona model -> "pong") seeds the session.
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, faker)
                .call(() -> agent.respond(original, "the original question"));

        // 2. Replay substituting the always-throwing boom model: the rerun turn must fail CLEANLY (partial),
        //    NOT propagate an uncaught exception out of replay() (the headline --model risk: an unreachable /
        //    unconfigured substitute provider).
        SubstitutionResult result = replayer.replay(original, ModelRef.parse("boom:test-model"));

        assertTrue(result.found());
        assertTrue(result.failed(), "a failing rerun turn is reported as partial, not propagated as a crash");
        assertEquals(0, result.turnCount(), "no turn completed under the throwing substitute model");
        assertNotNull(result.failureMessage(), "the failure names the turn that failed + its cause");
    }

    @Test
    void anUnresolvableSubstituteModelIsReportedAsFailedNotPropagated() {
        AgentId faker = new AgentId("faker");
        Agent agent = registry.getOrCreate(faker);
        String original = "cli:replay-resolve-fail-it";
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, faker)
                .call(() -> agent.respond(original, "the original question"));

        // An unknown provider fails at resolve time (before any turn) — it must be caught and reported as a
        // failed rerun, not propagate an uncaught exception out of replay().
        SubstitutionResult result = replayer.replay(original, ModelRef.parse("nonexistent:model"));

        assertTrue(result.found());
        assertTrue(result.failed(), "an unresolvable substitute provider is reported, not propagated");
        assertEquals(0, result.turnCount());
        assertNotNull(result.failureMessage(), "the failure explains the model could not be resolved");
    }
}
