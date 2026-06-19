package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.persistence.ProviderCallEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

/**
 * The end-to-end wiring proof for CAPR-driven adaptive routing (P3-4 #52): the {@code routed} agent
 * declares a two-link chain ({@code fake:sag-model} → {@code fake:healthy-model}, both on the in-process
 * {@link FakeModelProvider}). Seeding {@code provider_calls} so the PRIMARY sags and the FALLBACK is
 * healthy, a real turn through {@link Agent#respond} (which calls {@code LlmSelector.select}) must put the
 * healthy model FIRST — so the turn's own non-fallback {@code provider_calls} row records the healthy
 * model, not the declared primary. The [M18] green-for-wrong-reason guard: if {@code select} did NOT
 * reorder, the row would record the declared primary {@code sag-model}, so the assertion pins the actual
 * routing decision.
 */
@QuarkusTest
@TestProfile(AgentRegistryTestHomeProfile.class)
class CaprRoutingTurnIT {

    @Inject
    AgentRegistry registry;

    @Inject
    EntityManager em;

    private static final AgentId ROUTED = new AgentId("routed");
    private static final ModelRef SAG = new ModelRef("fake", "sag-model");
    private static final ModelRef HEALTHY = new ModelRef("fake", "healthy-model");

    /** Seed one provider_calls row in its own committed transaction so the turn's health read sees it. */
    private void seedCall(ModelRef ref, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            ProviderCallEntity p = new ProviderCallEntity();
            p.sessionId = "seed-session";
            p.agentId = ROUTED.value();
            p.provider = ref.provider();
            p.model = ref.model();
            p.tokensIn = 1;
            p.tokensOut = 1;
            p.latencyMs = 1;
            p.isFallback = 0;
            p.error = error;
            p.createdAt = System.currentTimeMillis();
            p.persist();
        });
    }

    @Test
    void saggingPrimaryIsRoutedBehindHealthyFallbackInARealTurn() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                ProviderCallEntity.delete("agentId", ROUTED.value()));

        // PRIMARY sags: 6 failures of 6 calls (>= min-attempts default 3).
        for (int i = 0; i < 6; i++) {
            seedCall(SAG, "java.lang.RuntimeException");
        }
        // FALLBACK is healthy: 6 successes.
        for (int i = 0; i < 6; i++) {
            seedCall(HEALTHY, null);
        }

        Agent agent = registry.getOrCreate(ROUTED);
        String sessionId = "routed-turn-1";
        ScopedValue.where(CurrentAgent.CURRENT_AGENT, ROUTED)
                .call(() -> agent.respond(sessionId, "hello"));

        // The turn's own (non-fallback) provider_calls row must record the HEALTHY model — proving the
        // router moved it ahead of the declared sagging primary.
        String head = (String) em.createNativeQuery(
                "select model from provider_calls "
              + "where session_id = :s and agent_id = :a and is_fallback = 0 order by id")
                .setParameter("s", sessionId)
                .setParameter("a", ROUTED.value())
                .getResultList().get(0);

        assertEquals(HEALTHY.model(), head,
                "the sagging declared primary must be down-ranked, so the healthy fallback fronts the turn");
    }
}
