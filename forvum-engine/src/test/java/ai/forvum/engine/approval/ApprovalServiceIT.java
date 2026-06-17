package ai.forvum.engine.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.sdk.ApprovalContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for {@link ApprovalService} — the P2-14 #39 blocking, SQLite-backed user-approval
 * queue. Boots Quarkus against a fresh temp SQLite with a short approval timeout so the timeout arm is
 * fast. Exercises every resolution mode: an interactive prompter (TTY), a non-interactive context
 * (immediate deny), and the async/dashboard path (a separate thread resolves the blocked call, or it
 * times out). Each test uses a distinct session id so rows do not collide in the shared instance DB.
 */
@QuarkusTest
@TestProfile(ApprovalServiceIT.ShortTimeoutProfile.class)
class ApprovalServiceIT {

    private static final AgentId AGENT = new AgentId("main");

    @Inject
    ApprovalService service;

    /**
     * Warm the request context + Agroal connection + Hibernate session before any timed/timeout-sensitive
     * assertion runs. The FIRST database op in this class pays Quarkus's lazy datasource init (observed at
     * ~5 s cold on a loaded macOS CI runner); paying it here, OUTSIDE the timed regions, keeps the async
     * approve/timeout tests from racing the configured timeout regardless of JUnit method order.
     */
    @BeforeEach
    void warmUpPersistence() {
        service.listPending();
    }

    @Test
    void interactiveApproveRunsAndMarksApproved() throws Exception {
        String id = ScopedValue.where(ApprovalContext.PROMPTER, (agentId, tool, args) -> true)
                .call(() -> {
                    boolean approved = service.requireApproval("sess-int-ok", AGENT, "a.danger", "{}");
                    assertTrue(approved, "an interactive approve must permit the call");
                    return latestPendingOrResolvedId("sess-int-ok");
                });
        assertEquals("approved", service.statusOf(id));
    }

    @Test
    void interactiveRejectMarksRejected() throws Exception {
        String id = ScopedValue.where(ApprovalContext.PROMPTER, (agentId, tool, args) -> false)
                .call(() -> {
                    boolean approved = service.requireApproval("sess-int-no", AGENT, "a.danger", "{}");
                    assertFalse(approved, "an interactive reject must deny the call");
                    return latestPendingOrResolvedId("sess-int-no");
                });
        assertEquals("rejected", service.statusOf(id));
    }

    @Test
    void nonInteractiveContextDeniesWithoutWaitingTheTimeout() throws Exception {
        // The immediate-deny path resolves REJECTED; the async-timeout path would resolve TIMED_OUT. So a
        // 'rejected' status proves the non-interactive branch was taken (it did NOT fall through to the
        // blocking timeout path) — a semantic check, robust on a slow/cold CI runner where a wall-clock
        // assertion is not (the first DB op alone can take seconds).
        String id = ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE)
                .call(() -> {
                    boolean approved = service.requireApproval("sess-noninteractive", AGENT, "a.danger", "{}");
                    assertFalse(approved, "a non-interactive context with no approval surface must deny");
                    return latestPendingOrResolvedId("sess-noninteractive");
                });
        assertEquals("rejected", service.statusOf(id),
                "non-interactive deny resolves 'rejected' (not 'timed_out'), proving it skipped the wait");
    }

    @Test
    void asyncDecisionApprovesTheBlockedCall() throws Exception {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> blocked = exec.submit(
                    () -> service.requireApproval("sess-async-ok", AGENT, "a.danger", "{}"));
            String id = awaitPendingId("sess-async-ok");
            assertTrue(service.decide(id, true, "looks fine"), "decide must complete the live future");
            assertTrue(blocked.get(8, TimeUnit.SECONDS), "an out-of-band approve must release the blocked turn");
            assertEquals("approved", service.statusOf(id));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void asyncTimeoutMarksTimedOutAndDenies() throws Exception {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> blocked = exec.submit(
                    () -> service.requireApproval("sess-async-timeout", AGENT, "a.danger", "{}"));
            String id = awaitPendingId("sess-async-timeout");
            // No decision arrives → the configured timeout (5s) fires and denies; wait past it with margin.
            assertFalse(blocked.get(15, TimeUnit.SECONDS), "an unresolved confirmation must time out to deny");
            assertEquals("timed_out", service.statusOf(id));
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Poll the pending queue until a row for {@code sessionId} appears (the async branch committed it). The
     * window (3s) stays under the configured timeout (5s) so the row is still {@code pending} when found,
     * and the {@code warmUpPersistence} step keeps the first {@code createPending} from eating it.
     */
    private String awaitPendingId(String sessionId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            String id = service.listPending().stream()
                    .filter(p -> p.sessionId().equals(sessionId))
                    .map(PendingApproval::id)
                    .findFirst().orElse(null);
            if (id != null) {
                return id;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no pending approval row appeared for " + sessionId);
    }

    /** The id of the (possibly already-resolved) row for a synchronous-path session. */
    private String latestPendingOrResolvedId(String sessionId) {
        return service.idForSession(sessionId)
                .orElseThrow(() -> new AssertionError("no approval row recorded for " + sessionId));
    }

    /**
     * A temp {@code $FORVUM_HOME} plus a 5s approval timeout: short enough that the timeout arm does not slow
     * the suite much, but long enough that the async-approve arm (which decides in milliseconds once the
     * persistence is warm, see {@code warmUpPersistence}) never races it on a loaded CI runner.
     */
    public static class ShortTimeoutProfile implements QuarkusTestProfile {
        static final Path HOME = createHome();

        private static Path createHome() {
            try {
                return Files.createTempDirectory("forvum-approval-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.approval.timeout-seconds", "5");
        }
    }
}
