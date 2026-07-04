package ai.forvum.engine.memory;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.memory.FactExtractor.ExtractedFact;
import ai.forvum.engine.memoryquery.EmbeddingSelector;
import ai.forvum.engine.memoryquery.SemanticMemoryStore;
import ai.forvum.engine.memoryquery.VectorCodec;
import ai.forvum.engine.security.OutputFilteredException;
import ai.forvum.engine.security.OutputGuardChain;
import ai.forvum.sdk.HookLayer;
import ai.forvum.sdk.OutputContext;

import dev.langchain4j.model.embedding.EmbeddingModel;

import io.opentelemetry.context.Context;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The off-turn Write phase of #175: after a turn completes, extract durable facts with the small model,
 * run each through the pre-memory-write filter, embed it, and upsert it into {@code semantic_memory} — so
 * a later turn can retrieve it (the loop {@link LocalMemoryProvider} reads). It runs on a managed VIRTUAL
 * THREAD so the extraction/embedding model calls never delay the reply (already streamed) and never enter
 * the per-turn latency budget.
 *
 * <p>Every tenant key is captured as a plain parameter at the call site (on the turn thread, where the
 * ScopedValues are bound) and passed into the async task, so the task itself needs NO ScopedValue re-bind
 * and does not touch {@code @AgentScoped} state. Two thread-crossing concerns ARE handled explicitly:
 * <ul>
 *   <li><b>Request context</b> — the extraction chat records a {@code provider_calls} row through the
 *       request-scoped {@code EntityManager}, so the async VT (which does not inherit the turn's context)
 *       activates its own {@link ManagedContext} for the write; {@code @ActivateRequestContext} cannot help
 *       here because the call is a self-invocation via the executor lambda.</li>
 *   <li><b>OTel context</b> — the turn's trace context is captured on the turn thread and re-established on
 *       the VT ({@link Context#wrap}), so the extraction model's span is a child of the turn (the §5.5
 *       {@code parent.wrap} worker pattern), not orphaned at the root.</li>
 * </ul>
 * Best-effort throughout: any failure is logged and swallowed; a memory-write problem never affects the
 * turn (which already returned) and never widens scope. A {@link Semaphore} bounds concurrent writes so a
 * burst of turns cannot starve the shared model server / connection pool. Disabled per-install with
 * {@code forvum.memory.write.enabled=false} (consent / opt-out).
 */
@ApplicationScoped
public class MemoryWriter {

    private static final Logger LOG = Logger.getLogger(MemoryWriter.class);

    /** Best-effort backpressure: at most this many off-turn writes run at once (bounds Ollama/pool pressure). */
    private static final int MAX_CONCURRENT_WRITES = 4;

    /** Grace window to drain in-flight writes on shutdown before force-cancelling them (see {@link #shutdown}). */
    private static final long WRITE_DRAIN_SECONDS = 3;

    @Inject
    FactExtractor factExtractor;

    @Inject
    SemanticMemoryStore semanticStore;

    @Inject
    EmbeddingSelector embeddings;

    @Inject
    OutputGuardChain outputGuards;

    @ConfigProperty(name = "forvum.memory.write.enabled", defaultValue = "true")
    boolean writeEnabled;

    @ConfigProperty(name = "forvum.memory.extraction-model", defaultValue = "ollama:qwen3:1.7b")
    String extractionModel;

    @ConfigProperty(name = "forvum.memory.embedding-model", defaultValue = "ollama:nomic-embed-text")
    String embeddingModel;

    /**
     * The async seam — a managed virtual-thread-per-task executor in production, overridden with a
     * same-thread executor ({@code Runnable::run}) in tests so the write is deterministically observable
     * (the {@code ApprovalService.dispatchExecutor} pattern, CLAUDE.md [P2-14]).
     */
    Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_WRITES);

    /**
     * Fire the Write phase for a completed turn. Returns immediately; the extraction/filter/embed/persist
     * work happens on the executor with the turn's OTel context re-established. A no-op when writing is
     * disabled. The caller (the turn) is responsible for firing this ONLY for a real interactive turn —
     * not under replay, not for a memory-disabled agent (see {@code Agent.respond}).
     */
    public void onTurnCompleted(AgentId agentId, String identityId, UUID turnId, String sessionId,
            String userText, String assistantText) {
        if (!writeEnabled) {
            return;
        }
        Runnable task = () -> writeFacts(agentId, identityId, turnId, sessionId, userText, assistantText);
        executor.execute(Context.current().wrap(task)); // propagate the turn's OTel trace context to the VT
    }

    /** The Write body — package-private so a test can drive it directly under a same-thread executor. */
    void writeFacts(AgentId agentId, String identityId, UUID turnId, String sessionId, String userText,
            String assistantText) {
        if (!slots.tryAcquire()) {
            LOG.debugf("Memory write skipped for agent '%s' — %d concurrent writes already in flight.",
                    agentId.value(), MAX_CONCURRENT_WRITES);
            return;
        }
        // Activate a request context on this async VT: the extraction chat's provider_calls ledger write and
        // any request-scoped read need one, and the turn thread's context does not cross to this VT.
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activatedHere = !requestContext.isActive();
        if (activatedHere) {
            requestContext.activate();
        }
        try {
            List<ExtractedFact> facts = factExtractor.extract(
                    ModelRef.parse(extractionModel), agentId.value(), sessionId, userText, assistantText);
            if (facts.isEmpty()) {
                return;
            }
            EmbeddingModel embedModel = embeddings.resolve(ModelRef.parse(embeddingModel));
            OutputContext ctx = new OutputContext(HookLayer.PRE_MEMORY_WRITE, agentId, turnId);
            for (ExtractedFact fact : facts) {
                persistFact(ctx, identityId, agentId.value(), turnId, embedModel, fact);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Memory write failed for agent '%s'; the turn is unaffected.", agentId.value());
        } finally {
            if (activatedHere) {
                requestContext.terminate();
            }
            slots.release();
        }
    }

    private void persistFact(OutputContext ctx, String identityId, String agentId, UUID turnId,
            EmbeddingModel embedModel, ExtractedFact fact) {
        String stored;
        try {
            // Pre-memory-write filter (DR-6a §9 point 2c): redact secrets/PII BEFORE the value is durably
            // stored, so it can never be re-retrieved into a later prompt. A Blocked outcome throws -> skip.
            stored = outputGuards.enforce(ctx, fact.value());
        } catch (OutputFilteredException e) {
            LOG.debugf("Memory fact '%s' blocked by the pre-memory-write guard; not stored.", fact.key());
            return;
        }
        float[] vector = embedModel.embed(stored).content().vector();
        byte[] blob = VectorCodec.encode(vector);
        semanticStore.upsertFact(identityId, agentId, fact.key(), stored, "turn:" + turnId, blob);
    }

    @PreDestroy
    void shutdown() {
        if (!(executor instanceof ExecutorService service)) {
            return; // a test same-thread executor (Runnable::run) has nothing to drain
        }
        service.shutdown();
        try {
            // Drain in-flight writes so none outlives the app: a one-shot `forvum ask` gets a brief window
            // to persist, and — critically — no write virtual thread lingers past shutdown to interfere with
            // the next boot (in a @QuarkusMainTest the following launch shares this JVM; a lingering write
            // touching the request context / EntityManager corrupts its startup). Force-cancel if it elapses.
            if (!service.awaitTermination(WRITE_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
