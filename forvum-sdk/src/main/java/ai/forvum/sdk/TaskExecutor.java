package ai.forvum.sdk;

import ai.forvum.core.TaskRecord;

/**
 * The write sink for the unified {@code tasks} ledger (ULTRAPLAN section 7.2 P2-TASKLEDGER): every
 * engine-initiated background task — a cron fire, a sub-agent spawn, other background work — is recorded
 * through {@link #record(TaskRecord)}.
 *
 * <p>This is a <strong>sink SPI</strong>, NOT a sealed provider in the channel/model/tool/memory
 * hierarchy: the single implementation (a {@code TaskRecorder} bean) lives in {@code forvum-engine}, and
 * plugins do NOT implement it. It is promoted to {@code forvum-sdk} only so the contract sits beside the
 * other Layer-0/Layer-1 types it depends on ({@link TaskRecord}), keeping the engine's recording seam an
 * interface a future query/observability surface can reuse — never on the engine itself. A plain
 * (non-sealed) interface: the engine is the producer-consumer, so there is no closed implementor set to
 * seal.
 *
 * <p>{@code forvum-sdk} is Quarkus-free; the contract takes a Layer-0 {@link TaskRecord} (a record), so
 * the write is reflection-free and native-safe. Operators query the ledger via direct SQL (no query DSL
 * in v0.5).
 */
public interface TaskExecutor {

    /** Persist {@code task} to the {@code tasks} ledger. */
    void record(TaskRecord task);
}
