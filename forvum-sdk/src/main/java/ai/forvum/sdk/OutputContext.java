package ai.forvum.sdk;

import ai.forvum.core.id.AgentId;

import java.util.UUID;

/**
 * The auditable context handed to an {@link OutputGuard#filter} call (ULTRAPLAN section 9.2.3, DR-6a):
 * the hook layer, the agent whose egress is inspected, and the turn id so a trip can be traced. A
 * {@code forvum-sdk} record over {@code forvum-core} + JDK types only (Quarkus-free).
 */
public record OutputContext(HookLayer layer, AgentId agentId, UUID turnId) {
    public OutputContext {
        if (layer == null) {
            throw new IllegalStateException("OutputContext.layer must not be null");
        }
    }
}
