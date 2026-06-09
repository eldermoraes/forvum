package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;

/**
 * The "is the model provider running?" hint fires for every root cause an unreachable provider
 * produces — including {@link ClosedChannelException}, how the JDK HTTP client surfaces a connection
 * refusal inside a native image (observed live; a {@code ConnectException}-only check missed it) —
 * and stays quiet for non-network failures so the hint never misleads.
 */
class TurnServiceConnectionFailureTest {

    @Test
    void connectionLevelRootCausesCarryTheProviderHint() {
        assertTrue(TurnService.isConnectionFailure(new ConnectException("Connection refused")));
        assertTrue(TurnService.isConnectionFailure(new ClosedChannelException()));
        assertTrue(TurnService.isConnectionFailure(new UnknownHostException("no-such-host")));
        assertTrue(TurnService.isConnectionFailure(new HttpConnectTimeoutException("timed out")));
    }

    @Test
    void nonNetworkFailuresDoNot() {
        assertFalse(TurnService.isConnectionFailure(new RuntimeException("boom")));
        assertFalse(TurnService.isConnectionFailure(new IllegalStateException("bad spec")));
    }
}
