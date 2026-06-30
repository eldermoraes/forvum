package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.DeviceCredential;
import ai.forvum.core.event.AgentEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link ChannelTurnDriver} backward-compatibility contract (#166): the credential-bearing
 * {@code dispatch(message, credential, sink)} is the canonical method, and the legacy two-arg
 * {@code dispatch(message, sink)} delegates to it with {@link DeviceCredential#ABSENT}. The default
 * three-arg overload lets the many channel-test fakes keep implementing only the legacy two-arg method
 * (they consume no credential), so adding the credential seam breaks none of them; the engine's
 * {@code TurnService} overrides the three-arg method to honor the credential.
 */
class ChannelTurnDriverTest {

    private static final ChannelMessage MSG = new ChannelMessage("web", "u-1", "hi", Instant.now());

    @Test
    void theDefaultCredentialOverloadDelegatesToTheLegacyDispatch() {
        // A fake that implements ONLY the legacy two-arg method (mirrors every channel-test fake).
        List<ChannelMessage> seen = new ArrayList<>();
        ChannelTurnDriver legacyOnly = (message, sink) -> seen.add(message);

        // Calling the new three-arg overload must route through the legacy method via the default impl,
        // so a fake that predates #166 keeps working (the credential is simply not consumed by it).
        legacyOnly.dispatch(MSG, new DeviceCredential("web", "a-secret"), noSink());

        assertEquals(1, seen.size(), "the default three-arg overload delegates to the legacy two-arg method");
        assertSame(MSG, seen.get(0));
    }

    private static Consumer<AgentEvent> noSink() {
        return event -> { };
    }
}
