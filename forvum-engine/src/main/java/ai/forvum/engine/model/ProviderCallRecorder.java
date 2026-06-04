package ai.forvum.engine.model;

/**
 * Write seam for the {@code provider_calls} ledger. The engine-local decorators depend only on this
 * interface; the Panache-backed implementation lives in the persistence layer, and tests use an
 * in-memory double — so the decorator logic is verifiable without a database.
 */
public interface ProviderCallRecorder {

    void record(ProviderCall call);
}
