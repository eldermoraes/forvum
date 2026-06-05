package ai.forvum.engine.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that collects {@link ToolInvocation}s in order, so executor logic is testable sans DB. */
public class InMemoryToolInvocationRecorder implements ToolInvocationRecorder {

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();

    @Override
    public void record(ToolInvocation invocation) {
        invocations.add(invocation);
    }

    /** The recorded invocations, in record order. */
    public List<ToolInvocation> invocations() {
        return invocations;
    }
}
