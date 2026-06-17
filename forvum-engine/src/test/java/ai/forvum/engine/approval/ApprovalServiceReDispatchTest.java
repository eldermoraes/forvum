package ai.forvum.engine.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Unit tests for the R1 restart-recovery + pre-approval logic of {@link ApprovalService} (P2-14 #39).
 * Constructed directly with in-memory doubles (a {@code @Vetoed} store subclass, a recording turn driver,
 * a same-thread dispatch executor), so the re-dispatch and pre-approval flow is verifiable without a
 * database or a Quarkus boot. The DB-backed resolution modes live in {@code ApprovalServiceIT}.
 */
class ApprovalServiceReDispatchTest {

    private ApprovalService service(RecordingStore store, ChannelTurnDriver driver) {
        ApprovalService service = new ApprovalService();
        service.store = store;
        service.turns = driver;
        service.dispatchExecutor = Runnable::run; // synchronous so the re-dispatch is observable
        return service;
    }

    @Test
    void orphanedApproveReDispatchesTheTurnAndResolvesApproved() {
        // Simulate a restart survivor: a pending row exists but no live future is registered in this process.
        RecordingStore store = new RecordingStore();
        store.pending = new PendingApproval("ap-1", "web:tab-7", "main", "shell.exec", "{\"cmd\":\"ls\"}", 10L);
        store.userMessage = "please list the files";
        RecordingDriver driver = new RecordingDriver();

        boolean handled = service(store, driver).decide("ap-1", true, "looks fine");

        assertTrue(handled, "an orphaned pending row must be handled");
        assertEquals(ApprovalStatus.APPROVED, store.resolvedStatus);
        ChannelMessage redispatched = driver.message.get();
        assertEquals("web", redispatched.channelId(), "channelId is the sessionId prefix");
        assertEquals("tab-7", redispatched.nativeUserId(), "nativeUserId is the sessionId suffix");
        assertEquals("please list the files", redispatched.content(), "re-dispatch replays the original prompt");
    }

    @Test
    void orphanedRejectResolvesRejectedWithoutReDispatch() {
        RecordingStore store = new RecordingStore();
        store.pending = new PendingApproval("ap-2", "web:tab-7", "main", "shell.exec", "{}", 10L);
        RecordingDriver driver = new RecordingDriver();

        boolean handled = service(store, driver).decide("ap-2", false, "nope");

        assertTrue(handled);
        assertEquals(ApprovalStatus.REJECTED, store.resolvedStatus);
        assertNull(driver.message.get(), "a reject must not re-dispatch the turn");
    }

    @Test
    void decideOnUnknownOrAlreadyResolvedIdReturnsFalse() {
        RecordingStore store = new RecordingStore();
        store.pending = null; // findPending returns empty
        RecordingDriver driver = new RecordingDriver();

        assertFalse(service(store, driver).decide("ghost", true, "x"));
        assertNull(driver.message.get());
    }

    @Test
    void orphanApproveGrantsTheExactCallOnlyWithinTheReplayTurnAndBindsNonInteractive() {
        // The pre-approval grant must be visible to requireApproval DURING the re-dispatched turn, carry the
        // NON_INTERACTIVE flag (so other confirm tools in the replay deny), and NOT leak outside the turn —
        // the turn-scoped ScopedValue cannot become a standing auto-approve for a later unrelated call.
        RecordingStore store = new RecordingStore();
        store.pending = new PendingApproval("ap-3", "web:tab-7", "main", "shell.exec", "{\"cmd\":\"ls\"}", 10L);
        store.userMessage = "list files";
        ProbingDriver driver = new ProbingDriver("web:tab-7", "shell.exec", "{\"cmd\":\"ls\"}");
        ApprovalService service = service(store, driver);
        driver.service = service;

        service.decide("ap-3", true, "ok");

        assertEquals(Boolean.TRUE, driver.preApprovedDuringReplay,
                "the exact call must be pre-approved DURING the re-dispatched turn");
        assertTrue(driver.nonInteractiveDuringReplay,
                "the replay must bind NON_INTERACTIVE so any OTHER confirm tool denies fast");
        assertFalse(service.isPreApprovedInScope("web:tab-7", "shell.exec", "{\"cmd\":\"ls\"}"),
                "the grant must NOT leak outside the replay turn (no standing auto-approve)");
    }

    /** A {@code @Vetoed} {@link ApprovalStore} double — overrides bypass the @Transactional interceptors. */
    @Vetoed
    static final class RecordingStore extends ApprovalStore {
        PendingApproval pending;
        String userMessage;
        ApprovalStatus resolvedStatus;

        @Override
        public Optional<PendingApproval> findPending(String id) {
            return Optional.ofNullable(pending);
        }

        @Override
        public void resolve(String id, ApprovalStatus status, String reason) {
            this.resolvedStatus = status;
        }

        @Override
        public Optional<String> userMessageFor(String id) {
            return Optional.ofNullable(userMessage);
        }

        @Override
        public String createPending(String sessionId, String agentId, String toolName, String arguments,
                String userMessage) {
            return "created";
        }
    }

    /** Records the single re-dispatched {@link ChannelMessage}; never delivers anywhere. */
    static final class RecordingDriver implements ChannelTurnDriver {
        final AtomicReference<ChannelMessage> message = new AtomicReference<>();

        @Override
        public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
            this.message.set(message);
        }
    }

    /** Probes, DURING the replay turn, whether the exact call is pre-approved and NON_INTERACTIVE is bound. */
    static final class ProbingDriver implements ChannelTurnDriver {
        ApprovalService service;
        private final String sessionId;
        private final String tool;
        private final String args;
        Boolean preApprovedDuringReplay;
        boolean nonInteractiveDuringReplay;

        ProbingDriver(String sessionId, String tool, String args) {
            this.sessionId = sessionId;
            this.tool = tool;
            this.args = args;
        }

        @Override
        public void dispatch(ChannelMessage message, Consumer<AgentEvent> sink) {
            preApprovedDuringReplay = service.isPreApprovedInScope(sessionId, tool, args);
            nonInteractiveDuringReplay = ApprovalContext.NON_INTERACTIVE.orElse(Boolean.FALSE);
            sink.accept(new Done(Instant.now(), UUID.randomUUID(), "ok"));
        }
    }
}
