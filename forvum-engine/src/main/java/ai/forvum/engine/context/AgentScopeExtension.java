package ai.forvum.engine.context;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;

import ai.forvum.core.AgentScoped;

/**
 * Registers the {@link AgentContext} for the {@link AgentScoped} normal scope via a CDI Lite
 * {@link BuildCompatibleExtension}. Declared in {@code META-INF/services} so ArC discovers and runs
 * it at build time — keeping {@code forvum-engine} a plain library (no Quarkus extension / deployment
 * module needed). {@code addContext} both wires the context and makes {@code @AgentScoped} a
 * bean-defining annotation.
 */
public class AgentScopeExtension implements BuildCompatibleExtension {

    @Discovery
    public void registerAgentScope(MetaAnnotations meta) {
        meta.addContext(AgentScoped.class, true, AgentContext.class);
    }
}
