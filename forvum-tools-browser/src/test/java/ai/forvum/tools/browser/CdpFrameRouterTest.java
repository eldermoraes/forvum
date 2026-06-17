package ai.forvum.tools.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.tools.browser.dto.CdpMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Socket-free unit tests for {@link CdpFrameRouter} — the inbound-frame correlation logic lifted out of the
 * (deleted) {@code @WebSocketClient} endpoint so it is exercised with no live Chrome and no WebSocket. A raw
 * text frame fed to {@link CdpFrameRouter#onFrame} either completes the matching pending command future
 * (a response) or forwards an unsolicited event to the registered listener; an unparseable frame is dropped.
 */
class CdpFrameRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CdpFrameRouter router() {
        return new CdpFrameRouter(new CdpProtocol(MAPPER));
    }

    @Test
    void aResponseFrameCompletesThePendingCommandFutureWithItsResult() throws Exception {
        CdpFrameRouter router = router();
        CompletableFuture<JsonNode> future = router.awaitResponse(5L);

        router.onFrame("{\"id\":5,\"result\":{\"frameId\":\"F1\"}}");

        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertEquals("F1", result.get("frameId").asText());
    }

    @Test
    void anErrorResponseFrameCompletesThePendingFutureExceptionally() {
        CdpFrameRouter router = router();
        CompletableFuture<JsonNode> future = router.awaitResponse(6L);

        router.onFrame("{\"id\":6,\"error\":{\"code\":-32000,\"message\":\"Cannot navigate\"}}");

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CdpException);
        assertTrue(ex.getCause().getMessage().contains("Cannot navigate"));
    }

    @Test
    void anUnsolicitedEventIsForwardedToTheListener() {
        CdpFrameRouter router = router();
        List<CdpMessage> events = new ArrayList<>();
        router.setEventListener(events::add);

        router.onFrame("{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":12.3}}");

        assertEquals(1, events.size());
        assertEquals("Page.loadEventFired", events.get(0).method());
    }

    @Test
    void aResponseToAForgottenCommandIsSilentlyIgnored() {
        // The future for id 9 was forgotten (timed out); a late response must neither throw nor complete
        // the abandoned future (it is no longer in the pending map).
        CdpFrameRouter router = router();
        CompletableFuture<JsonNode> abandoned = router.awaitResponse(9L);
        router.forget(9L);

        router.onFrame("{\"id\":9,\"result\":{}}");

        assertFalse(abandoned.isDone(), "a late response to a forgotten id must complete nothing");
    }

    @Test
    void anUnparseableFrameIsDroppedNotThrown() {
        CdpFrameRouter router = router();
        router.onFrame("{ not json"); // swallowed
    }

    @Test
    void forgetDropsAPendingIdSoItDoesNotLeak() {
        CdpFrameRouter router = router();
        CompletableFuture<JsonNode> future = router.awaitResponse(3L);
        router.forget(3L);
        // A later response to the forgotten id is ignored (no future left in the map to complete).
        router.onFrame("{\"id\":3,\"result\":{}}");

        assertFalse(future.isDone(), "after forget, a late response to that id leaves the future uncompleted");
    }

    @Test
    void closeFailsEveryOutstandingCommandAndClearsTheListener() {
        CdpFrameRouter router = router();
        CompletableFuture<JsonNode> a = router.awaitResponse(1L);
        CompletableFuture<JsonNode> b = router.awaitResponse(2L);
        List<CdpMessage> events = new ArrayList<>();
        router.setEventListener(events::add);

        router.onClose();

        assertTrue(a.isCompletedExceptionally());
        assertTrue(b.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, () -> a.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CdpException);

        // The listener is cleared on close: a late event is no longer forwarded.
        router.onFrame("{\"method\":\"Page.loadEventFired\",\"params\":{}}");
        assertTrue(events.isEmpty(), "the event listener is cleared on close");
    }

    @Test
    void anEventWithNoRegisteredListenerIsSafelyDropped() {
        CdpFrameRouter router = router();
        assertNull(router.eventListenerForTest(), "no listener registered initially");
        router.onFrame("{\"method\":\"Page.loadEventFired\",\"params\":{}}"); // no NPE
        assertFalse(router.awaitResponse(1L).isDone(), "a fresh pending future is still open");
    }
}
