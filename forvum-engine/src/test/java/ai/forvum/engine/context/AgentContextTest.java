package ai.forvum.engine.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import ai.forvum.core.AgentScoped;
import ai.forvum.core.id.AgentId;

import org.junit.jupiter.api.Test;

/** Pure unit test for {@link AgentContext} SPI behavior and the M7 {@link AgentContext#destroy(AgentId)}
 *  eviction path — no CDI/Quarkus boot. */
class AgentContextTest {

    /** Minimal Contextual that counts create/destroy and returns a fresh instance each create. */
    private static final class CountingContextual implements Contextual<Object> {
        int created = 0;
        int destroyed = 0;

        @Override
        public Object create(CreationalContext<Object> creationalContext) {
            created++;
            return new Object();
        }

        @Override
        public void destroy(Object instance, CreationalContext<Object> creationalContext) {
            destroyed++;
        }
    }

    private static final class NoopCreationalContext implements CreationalContext<Object> {
        @Override
        public void push(Object incompleteInstance) {
        }

        @Override
        public void release() {
        }
    }

    @Test
    void scopeIsActiveOnlyWhenAgentBound() throws Exception {
        AgentContext ctx = new AgentContext();
        assertEquals(AgentScoped.class, ctx.getScope());
        assertTrue(ctx.isNormal());
        assertFalse(ctx.isActive());

        boolean activeInside = ScopedValue.where(CurrentAgent.CURRENT_AGENT, new AgentId("a"))
                .call(ctx::isActive);
        assertTrue(activeInside);
    }

    @Test
    void destroyByAgentIdEvictsAndDestroysHeldBeans() throws Exception {
        AgentContext ctx = new AgentContext();
        CountingContextual contextual = new CountingContextual();
        AgentId a = new AgentId("agent-destroy");

        Object first = ScopedValue.where(CurrentAgent.CURRENT_AGENT, a)
                .call(() -> ctx.get(contextual, new NoopCreationalContext()));
        Object cached = ScopedValue.where(CurrentAgent.CURRENT_AGENT, a)
                .call(() -> ctx.get(contextual));
        assertSame(first, cached, "same agent must return the cached instance");
        assertEquals(1, contextual.created, "exactly one instance created");

        ctx.destroy(a);
        assertEquals(1, contextual.destroyed, "eviction must destroy the held bean once");

        Object afterEvict = ScopedValue.where(CurrentAgent.CURRENT_AGENT, a)
                .call(() -> ctx.get(contextual));
        assertNull(afterEvict, "after eviction the bean is gone (null create context -> no recreate)");
    }
}
