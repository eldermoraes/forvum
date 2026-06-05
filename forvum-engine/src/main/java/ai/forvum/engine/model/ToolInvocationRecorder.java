package ai.forvum.engine.model;

/**
 * Write seam for the {@code tool_invocations} ledger. The engine's {@code ToolExecutor} depends only on
 * this interface; the Panache-backed implementation lives in the persistence layer, and tests use an
 * in-memory double — so the executor logic is verifiable without a database (mirrors
 * {@link ProviderCallRecorder}).
 */
public interface ToolInvocationRecorder {

    void record(ToolInvocation invocation);
}
