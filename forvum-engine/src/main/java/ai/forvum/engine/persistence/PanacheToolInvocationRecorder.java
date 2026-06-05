package ai.forvum.engine.persistence;

import ai.forvum.engine.model.ToolInvocation;
import ai.forvum.engine.model.ToolInvocationRecorder;

import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/** Panache-backed {@link ToolInvocationRecorder}: maps a {@link ToolInvocation} to a row in tool_invocations. */
@Singleton
public class PanacheToolInvocationRecorder implements ToolInvocationRecorder {

    @Override
    @Transactional
    public void record(ToolInvocation invocation) {
        ToolInvocationEntity entity = new ToolInvocationEntity();
        entity.sessionId = invocation.sessionId();
        entity.agentId = invocation.agentId();
        entity.toolName = invocation.toolName();
        entity.arguments = invocation.arguments();
        entity.result = invocation.result();
        entity.status = invocation.status().dbValue();
        entity.latencyMs = invocation.latencyMs();
        entity.createdAt = invocation.createdAt();
        entity.persist();
    }
}
